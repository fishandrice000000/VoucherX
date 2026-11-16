package com.voucherx.fulfillment;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.seckill.service.SeckillService;
import com.voucherx.seckill.service.SeckillStockInitializer;
import com.voucherx.seckill.service.SeckillStockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 当前协议的真实组件回归：只支持显式身份、完整门禁及新建库基线，不修改本机开发数据。 */
@SpringBootTest(properties = "voucherx.delivery.enabled=false")
class CurrentContractIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private SeckillStockInitializer initializer;
    @Autowired
    private SeckillStockService stock;
    @Autowired
    private SeckillService service;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager manager;
    @Autowired
    @Qualifier("seckillReserveScript")
    private DefaultRedisScript<Long> reserve;
    @Autowired
    @Qualifier("admissionAcquireScript")
    private DefaultRedisScript<Long> admission;

    /** 请求必须携带 Bearer 和有效幂等键；拒绝时不创建意图，同键重试返回原请求。 */
    @Test
    void httpContractRejectsOldFormsAndReplaysStableKeys() throws Exception {
        createUser(1);
        activity(1);
        assertThat(initializer.initializeSafely(1L)).isTrue();
        redisTemplate.opsForValue().set(RedisKeys.token("current-token"), "1");
        mockMvc.perform(post("/api/seckill/vouchers/1").header("Authorization", "current-token")
                .header("Idempotency-Key", "buy-1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/seckill/vouchers/1").header("Authorization", "Bearer current-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        for (String key : List.of(" ", "bad key", "x".repeat(65)))
            mockMvc.perform(post("/api/seckill/vouchers/1").header("Authorization", "Bearer current-token")
                    .header("Idempotency-Key", key)).andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_seckill_request", Long.class)).isZero();
        assertThat(stock.stock(1L)).isEqualTo(10);
        mockMvc.perform(post("/api/seckill/vouchers/1").header("Authorization", "Bearer current-token")
                .header("Idempotency-Key", "buy-1")).andExpect(status().isAccepted());
        mockMvc.perform(post("/api/seckill/vouchers/1").header("Authorization", "Bearer current-token")
                .header("Idempotency-Key", "buy-1")).andExpect(status().isAccepted());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_seckill_request", Long.class)).isEqualTo(1);
        assertThat(stock.stock(1L)).isEqualTo(9);
    }

    /**
     * 验证直接服务调用必须携带幂等键，且请求查询以当前用户拥有的请求账本为依据。
     */
    @Test
    void serviceRequiresKeyAndLedgerEvenWhenAnOrderExists() {
        assertThatThrownBy(() -> service.seckill(1L, 1L, null)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_PARAM));
        jdbcTemplate.update("""
                INSERT INTO tb_voucher_order
                (request_id,message_id,user_id,voucher_id,stock_mode,bucket_no,layout_version,completed_at_ms)
                VALUES ('orphan','event',1,1,'SINGLE',0,1,100)
                """);
        assertThatThrownBy(() -> service.queryRequest(1L, "orphan")).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_NOT_FOUND));
    }

    /** 验证表结构可加载演示种子并建立活动门禁，重复初始化保留当前 Redis 库存。 */
    @Test
    void freshSchemaAndSeedInitializeCurrentState() {
        new ResourceDatabasePopulator(new ClassPathResource("db/seed.sql")).execute(dataSource);
        assertThat(initializer.initializeSafely(1L)).isTrue();
        assertThat(redisTemplate.opsForHash().entries(RedisKeys.seckillGate(1L)))
                .containsEntry("state", "NORMAL").containsEntry("epoch", "1");
        redisTemplate.opsForValue().set(RedisKeys.seckillStock(1L), "7");
        assertThat(initializer.initializeSafely(1L)).isTrue();
        assertThat(stock.stock(1L)).isEqualTo(7);
    }

    /** 验证仅完成 gate 写入时可继续初始化，已有库存的门禁须完整且代次匹配。 */
    @Test
    void initializationResumesOnlyWithConsistentExplicitState() {
        activity(1);
        redisTemplate.opsForHash().putAll(RedisKeys.seckillGate(1L), Map.of("state", "NORMAL", "epoch", "1"));
        assertThat(initializer.initializeSafely(1L)).isTrue();
        redisTemplate.delete(RedisKeys.seckillGate(1L));
        assertThat(initializer.initializeSafely(1L)).isFalse();
        assertThat(stock.stock(1L)).isEqualTo(10);
        assertThatThrownBy(() -> service.seckill(1L, 1L, "missing-gate"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_STOCK_UNAVAILABLE));
        assertThat(stock.stock(1L)).isEqualTo(10);
        redisTemplate.opsForHash().putAll(RedisKeys.seckillGate(1L), Map.of("state", "NORMAL", "epoch", "2"));
        assertThat(initializer.initializeSafely(1L)).isFalse();
        assertThat(redisTemplate.opsForHash().get(RedisKeys.seckillGate(1L), "epoch")).isEqualTo("2");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Long.class)).isZero();
    }

    /** 冻结事务持有控制行锁期间启动初始化，初始化必须等待并读取已提交的冻结状态。 */
    @Test
    void initializationCannotOverwriteConcurrentFreeze() throws Exception {
        activity(1);
        assertThat(initializer.initializeSafely(1L)).isTrue();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var freezing = workers.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE tb_activity_control SET state='FROZEN',epoch=2 WHERE voucher_id=1");
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("测试栅栏超时");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                redisTemplate.opsForHash().putAll(RedisKeys.seckillGate(1L), Map.of("state", "FROZEN", "epoch", "2"));
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var started = new CountDownLatch(1);
                var initializing = workers.submit(() -> {
                    started.countDown();
                    return initializer.initializeSafely(1L);
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                freezing.get(5, TimeUnit.SECONDS);
                assertThat(initializing.get(5, TimeUnit.SECONDS)).isFalse();
            } finally {
                release.countDown();
            }
        }
        assertThat(redisTemplate.opsForHash().entries(RedisKeys.seckillGate(1L)))
                .containsEntry("state", "FROZEN").containsEntry("epoch", "2");
        assertThat(stock.stock(1L)).isEqualTo(10);
    }

    /**
     * 验证 Lua 拒绝缺少必需参数的调用，并保持预留、释放和准入数据原值。
     */
    @Test
    void luaRequiresExplicitGenerationAndAdmissionMode() {
        activity(1);
        assertThat(initializer.initializeSafely(1L)).isTrue();
        var keys = List.of(RedisKeys.seckillStock(1L), RedisKeys.seckillUsers(1L),
                RedisKeys.seckillRequest("incomplete"),
                RedisKeys.seckillOwners(1L), RedisKeys.seckillGate(1L));
        long now = System.currentTimeMillis();
        assertThat(redisTemplate.execute(reserve, keys, "1", "incomplete", String.valueOf(now), "0",
                String.valueOf(now + 10000), "1", "SINGLE", "0", "1")).isEqualTo(-6);
        redisTemplate.delete(RedisKeys.seckillGate(1L));
        assertThat(stock.release(1L, 1L, "incomplete", "TEST", 1)).isFalse();
        assertThat(stock.stock(1L)).isEqualTo(10);
        assertThat(redisTemplate.hasKey(RedisKeys.seckillRequest("incomplete"))).isFalse();
        var admissionKeys = List.of(RedisKeys.admissionSharedTokens(), RedisKeys.admissionSharedPolicy(),
                RedisKeys.admissionActivityTokens(1L), RedisKeys.admissionActivityPolicy(1L));
        assertThat(redisTemplate.execute(admission, admissionKeys, "1", "100", "100", "100", "100")).isEqualTo(-4);
        for (String key : admissionKeys)
            assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    /** 为初始化协议测试准备一场未消费的 SINGLE 活动。 */
    private void activity(long id) {
        createActivity(id, 10, 10, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
    }
}
