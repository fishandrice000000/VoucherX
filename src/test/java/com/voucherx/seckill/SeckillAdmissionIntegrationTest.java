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
 * FIXED 全局准入与抢券编排的组件测试：关闭本地桶，只验证 Redis 双层预算在预留前阻止请求。
 * <p>
 * 直接调用 SeckillService，所以断言的是业务分类和 Redis 库存/身份副作用，不通过 HTTP 包装层。
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

    /**
     * 共享和活动容量均为 1，首个用户预留后第二个用户被全局拒绝；Redis 只扣一份库存，资格和请求 Hash 只属于首个用户。
     */
    @Test
    void globalRejectionHasNoStockOrRequestSideEffects() {
        createUser(1);
        createUser(2);
        createActivity(VOUCHER_ID, 10, 10, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 10);

        SeckillAcceptResponse accepted = seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString());
        assertThat(accepted.requestId()).isNotBlank();

        assertThatThrownBy(() -> seckillService.seckill(2L, VOUCHER_ID, java.util.UUID.randomUUID().toString()))
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

    /**
     * 第一活动耗尽共享预算后，换另一个活动仍被拒绝；第二活动库存保持 10、第二用户没有领取资格。
     */
    @Test
    void sharedBudgetIsNotBypassedBySwitchingActivity() {
        createUser(1);
        createUser(2);
        LocalDateTime begin = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(1);
        createActivity(VOUCHER_ID, 10, 10, begin, end);
        createActivity(OTHER_VOUCHER_ID, 10, 10, begin, end);
        initializeRedisState(VOUCHER_ID, 10);
        initializeRedisState(OTHER_VOUCHER_ID, 10);

        seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString());
        assertThatThrownBy(() -> seckillService.seckill(2L, OTHER_VOUCHER_ID, java.util.UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_BUSY));

        assertThat(stockService.stock(OTHER_VOUCHER_ID)).isEqualTo(10L);
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeys.seckillUsers(OTHER_VOUCHER_ID), "2")).isFalse();
    }
}
