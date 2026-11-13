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
 * 本地入口保护的 HTTP 组件测试：FIXED、本地容量 1 且极慢补充，关闭全局桶以隔离本地分支。
 * <p>
 * 通过 MockMvc 与认证上下文调用，验证第二个请求的 429 及错误码，并确认它尚未占库存资格。
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

    /**
     * 两个用户带各自 Token 抢同一活动，首个返回 202，第二个返回 429/42920；Redis 只有第一位用户的资格，库存只少 1。
     *
     * @throws Exception
     *                       HTTP 测试执行异常
     */
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

    /**
     * 直接往隔离 Redis 写随机 Token 到用户 ID 的映射，跳过验证码登录以聚焦本地准入；不是生产登录入口。
     *
     * @param userId
     *                   已准备的用例用户 ID
     * @return 用于 MockMvc Authorization 头的 Token
     */
    private String issueToken(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(RedisKeys.token(token), String.valueOf(userId));
        return token;
    }
}
