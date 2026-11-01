package com.voucherx.seckill;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.seckill.service.SeckillStockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地桶容量为 1 时，同一实例的第二个请求必须在库存预扣前被 429 拒绝。
 */
@TestPropertySource(properties = {
        "voucherx.admission.mode=FIXED",
        "voucherx.admission.local.enabled=true",
        "voucherx.admission.local.rate-per-second=0.001",
        "voucherx.admission.local.capacity=1",
        "voucherx.admission.global.enabled=false"
})
class SeckillLocalAdmissionIntegrationTest extends AbstractIntegrationTest {

    private static final long VOUCHER_ID = 2102L;

    @Autowired
    private SeckillStockService stockService;

    @Test
    void localBurstRejectionReturns429BeforeReservation() throws Exception {
        createUser(1);
        createUser(2);
        createActivity(VOUCHER_ID, 10, 10, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 10);

        String token1 = issueToken(1L);
        String token2 = issueToken(2L);

        mockMvc.perform(post("/api/seckill/vouchers/{id}", VOUCHER_ID)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/seckill/vouchers/{id}", VOUCHER_ID)
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42920));

        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(9L);
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeys.seckillUsers(VOUCHER_ID), "1")).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeys.seckillUsers(VOUCHER_ID), "2")).isFalse();
    }

    private String issueToken(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(RedisKeys.token(token), String.valueOf(userId));
        return token;
    }
}
