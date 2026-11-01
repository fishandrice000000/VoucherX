package com.voucherx.seckill;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.seckill.dto.SeckillAcceptResponse;
import com.voucherx.seckill.service.SeckillService;
import com.voucherx.seckill.service.SeckillStockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定全局限流：同一活动共享令牌耗尽后，第二个请求必须在预扣前被拒绝。
 * 本测试类关闭本地桶，单独验证 Redis 双层令牌桶。
 */
@TestPropertySource(properties = {
        "voucherx.admission.mode=FIXED",
        "voucherx.admission.local.enabled=false",
        "voucherx.admission.global.shared-rate=1",
        "voucherx.admission.global.shared-capacity=1",
        "voucherx.admission.global.activity-rate=1",
        "voucherx.admission.global.activity-capacity=1"
})
class SeckillAdmissionIntegrationTest extends AbstractIntegrationTest {

    private static final long VOUCHER_ID = 2101L;
    private static final long OTHER_VOUCHER_ID = 2102L;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillStockService stockService;

    @Test
    void globalRejectionHasNoStockOrRequestSideEffects() {
        createUser(1);
        createUser(2);
        createActivity(VOUCHER_ID, 10, 10, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 10);

        SeckillAcceptResponse accepted = seckillService.seckill(1L, VOUCHER_ID);
        assertThat(accepted.requestId()).isNotBlank();

        assertThatThrownBy(() -> seckillService.seckill(2L, VOUCHER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_BUSY));

        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(9L);
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeys.seckillUsers(VOUCHER_ID), "1")).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeys.seckillUsers(VOUCHER_ID), "2")).isFalse();
        Set<String> requestKeys = redisTemplate.keys("voucherx:seckill:request:*");
        assertThat(requestKeys).containsExactly(RedisKeys.seckillRequest(accepted.requestId()));
    }

    @Test
    void sharedBudgetIsNotBypassedBySwitchingActivity() {
        createUser(1);
        createUser(2);
        LocalDateTime begin = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(1);
        createActivity(VOUCHER_ID, 10, 10, begin, end);
        createActivity(OTHER_VOUCHER_ID, 10, 10, begin, end);
        stockService.initializeIfAbsent(VOUCHER_ID, 10);
        stockService.initializeIfAbsent(OTHER_VOUCHER_ID, 10);

        seckillService.seckill(1L, VOUCHER_ID);
        assertThatThrownBy(() -> seckillService.seckill(2L, OTHER_VOUCHER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_BUSY));

        assertThat(stockService.stock(OTHER_VOUCHER_ID)).isEqualTo(10L);
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeys.seckillUsers(OTHER_VOUCHER_ID), "2")).isFalse();
    }
}
