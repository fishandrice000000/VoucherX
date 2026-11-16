package com.voucherx.fulfillment;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.seckill.service.SeckillService;
import com.voucherx.seckill.service.SeckillStockInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.time.Duration;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * 真实组件回归，串联受理投递、购买付款、用券消费、关闭补偿及活动恢复。
 * <p>
 * MySQL、Redis 和 RabbitMQ 来自隔离容器；用例关闭自动 Outbox 投递并显式 dispatch 推进消息。
 * 先读 realComponentsDeliverAcceptedRequestAndReplayIdempotently，再读
 * realPurchaseIssueCancelRelockAndRedeem。
 *
 * @see #realComponentsDeliverAcceptedRequestAndReplayIdempotently()
 * @see #realPurchaseIssueCancelRelockAndRedeem()
 */
@TestPropertySource(properties = { "voucherx.delivery.enabled=false", "voucherx.commerce.simulation-enabled=true",
        "voucherx.fault.enabled=true" })
class DeliveryComponentTest extends AbstractIntegrationTest {
    @Autowired
    SeckillService service;
    @Autowired
    SeckillStockInitializer initializer;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    RequestLedger ledger;
    @Autowired
    com.voucherx.commerce.CommerceService commerce;
    @Autowired
    com.voucherx.reconcile.CompensationService compensation;
    @Autowired
    com.voucherx.order.service.PurchaseOrderCreationService persistence;
    @Autowired
    com.voucherx.reconcile.ReconciliationService reconciliation;
    @Autowired
    com.voucherx.recovery.RecoveryService recovery;
    @Autowired
    com.voucherx.recovery.FaultInjector faults;

    /**
     * 每个用例结束清除故障注入状态，避免一个用例布置的触发点影响后续业务流。
     */
    @org.junit.jupiter.api.AfterEach
    void clearFaults() {
        faults.clear();
    }

    /**
     * 用户/活动均为 1、库存 10，先抢券并确认请求是 ACCEPTED，此时尚未手动投递。
     * <p>
     * 调用真实 dispatch 后等待 MQ 消费将请求改为 SUCCEEDED，再复用 smoke-1 幂等键找回同一 requestId。
     * 最终数据库一笔购买单、库存 9；把“可靠受理”“发布”和“事务建单”三个阶段逐步连起来观察。
     */
    @Test
    void realComponentsDeliverAcceptedRequestAndReplayIdempotently() {
        createUser(1);
        createActivity(1, 10, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5));
        assertThat(initializer.initializeSafely(1L)).isTrue();
        var accepted = service.seckill(1L, 1L, "smoke-1");
        assertThat(ledger.find(accepted.requestId()).phase()).isEqualTo("ACCEPTED");
        assertThat(dispatcher.dispatch()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(ledger.find(accepted.requestId()).phase()).isEqualTo("SUCCEEDED"));
        assertThat(service.seckill(1L, 1L, "smoke-1").requestId()).isEqualTo(accepted.requestId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(1);
        assertThat(
                jdbcTemplate.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=1", Integer.class))
                .isEqualTo(9);
    }

    /**
     * 顺序执行真实组件链路：Outbox/MQ 建购买单、付款发券、消费锁券、取消释放、重新锁券及付款核销。
     * <p>
     * 用户与活动均为 1，购买支付 100 分、券面额 1000 分、消费原额 2000 分、应付 1000 分。
     * 相同 pay-buy 事件返回首次 APPLIED 回执且只发一券；取消后的消费单收到迟到成功通知时返回 CONFLICT_TERMINAL。
     * <p>
     * 新消费单付款返回 APPLIED，新的成功事件返回 ALREADY_PAID，最终券为 USED 且只有一条 USE。
     * 此用例覆盖上述顺序操作，锁券并发与事务回滚见 PersistenceContractTest。
     *
     * @see PersistenceContractTest
     */
    @Test
    void realPurchaseIssueCancelRelockAndRedeem() {
        createUser(1);
        createActivity(1, 10, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5));
        initializer.initializeSafely(1L);
        var accepted = service.seckill(1L, 1L, "commerce");
        dispatcher.dispatch();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(ledger.find(accepted.requestId()).orderId()).isNotNull());
        long purchase = ledger.find(accepted.requestId()).orderId();
        var purchaseKind = com.voucherx.commerce.CommerceService.OrderKind.PURCHASE;
        var consumeKind = com.voucherx.commerce.CommerceService.OrderKind.CONSUMPTION;
        assertThat(commerce.simulatePayment(1, purchaseKind, purchase, "pay-buy", 100, true).outcome())
                .isEqualTo("APPLIED");
        assertThat(commerce.simulatePayment(1, purchaseKind, purchase, "pay-buy", 100, true).outcome())
                .isEqualTo("APPLIED");
        assertThat(commerce.coupons(1, 0, 20)).hasSize(1);
        long coupon = commerce.coupons(1, 0, 20).getFirst().id();
        long cancelled = commerce.createConsumptionOrder(1, 1, coupon, 2000);
        assertThat(commerce.cancel(1, consumeKind, cancelled)).isEqualTo("CANCELLED");
        long consume = commerce.createConsumptionOrder(1, 1, coupon, 2000);
        assertThat(commerce.simulatePayment(1, consumeKind, cancelled, "late-pay", 1000, true).outcome())
                .isEqualTo("CONFLICT_TERMINAL");
        assertThat(commerce.simulatePayment(1, consumeKind, consume, "pay-use", 1000, true).outcome())
                .isEqualTo("APPLIED");
        assertThat(commerce.simulatePayment(1, consumeKind, consume, "pay-use-again", 1000, true).outcome())
                .isEqualTo("ALREADY_PAID");
        assertThat(commerce.getCoupon(coupon).state()).isEqualTo("USED");
        assertThat(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_coupon_action WHERE action='USE'", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> commerce.cancel(1, consumeKind, consume))
                .isInstanceOf(com.voucherx.common.exception.BusinessException.class);
    }

    /**
     * 真实组件补偿链路，从用户 1、活动 1、库存 10 的受理未投递请求开始。
     * <p>
     * closeRequestAndPrepareCompensation 提交请求关闭，再执行两次
     * releaseReservationAndRecordResult；Redis 库存最终为 10，关闭请求的建单被拒绝，订单数为 0，
     * 重新诊断为 WAIT。该用例验证补偿重复执行的幂等性及请求终态屏障。
     *
     * @see com.voucherx.reconcile.CompensationService#closeRequestAndPrepareCompensation
     * @see com.voucherx.reconcile.CompensationService#releaseReservationAndRecordResult
     */
    @Test
    void durableClosureAndRepeatedCompensationReturnStockOnce() {
        createUser(1);
        createActivity(1, 10, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5));
        initializer.initializeSafely(1L);
        var accepted = service.seckill(1L, 1L, "compensate");
        var message = ledger.find(accepted.requestId()).message();
        compensation.closeRequestAndPrepareCompensation(accepted.requestId(), "SMOKE_CLOSED");
        assertThat(compensation.releaseReservationAndRecordResult(accepted.requestId())).isEqualTo("RELEASED");
        assertThat(compensation.releaseReservationAndRecordResult(accepted.requestId())).isEqualTo("RELEASED");
        assertThat(redisTemplate.opsForValue().get(com.voucherx.common.redis.RedisKeys.seckillStock(1L)))
                .isEqualTo("10");
        assertThatThrownBy(() -> persistence.createPurchaseOrder(message))
                .isInstanceOf(com.voucherx.order.service.OrderBusinessException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isZero();
        assertThat(reconciliation.diagnose(accepted.requestId()).action())
                .isEqualTo(com.voucherx.reconcile.ReconcileDecision.WAIT);
    }

    /**
     * 验证持久化受理后 Redis 库存、资格、归属和请求 Hash 丢失时，活动恢复保留在途占用。
     * <p>
     * 总额 10、C=0、R=1，恢复后 A=9，gate 保持存在。冻结提升 epoch，先前代次消息的建单和失败回写均被拒绝。
     * 当前代次账本由同一事件的 PENDING Outbox 再次投递，最终请求 SUCCEEDED，MySQL 库存为 9。
     * 用例运行于隔离 MySQL、Redis 和 RabbitMQ 容器。
     */
    @Test
    void rebuildPreservesAcceptedRequestAndRejectsOldEpoch() {
        createUser(1);
        createActivity(1, 10, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5));
        initializer.initializeSafely(1L);
        var accepted = service.seckill(1L, 1L, "recover");
        var old = ledger.find(accepted.requestId()).message();
        recovery.freeze(1, "COMPONENT_SMOKE");
        redisTemplate.delete(java.util.List.of(com.voucherx.common.redis.RedisKeys.seckillStock(1L),
                com.voucherx.common.redis.RedisKeys.seckillUsers(1L),
                com.voucherx.common.redis.RedisKeys.seckillOwners(1L),
                com.voucherx.common.redis.RedisKeys.seckillRequest(accepted.requestId())));
        assertThat(recovery.recover(1).state()).isEqualTo("NORMAL");
        assertThat(redisTemplate.opsForValue().get(com.voucherx.common.redis.RedisKeys.seckillStock(1L)))
                .isEqualTo("9");
        assertThatThrownBy(() -> persistence.createPurchaseOrder(old))
                .isInstanceOf(com.voucherx.order.service.OrderBusinessException.class);
        assertThat(ledger.find(accepted.requestId()).phase()).isEqualTo("ACCEPTED");
        assertThat(dispatcher.dispatch()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(ledger.find(accepted.requestId()).phase()).isEqualTo("SUCCEEDED"));
        assertThat(
                jdbcTemplate.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=1", Integer.class))
                .isEqualTo(9);
    }

    /**
     * 验证 Redis 发布成功、数据库重建阶段尚未提交时抛异常，重入不会重复增加库存。
     * <p>
     * 总额 10、R=C=0；RECOVERY_PUBLISHED 注入 FAIL 后，Redis 已被覆盖，数据库阶段回滚为 RECONCILING。
     * 再次 recover 从重建继续，最终 NORMAL 且库存仍为 10，同时覆盖零成员的发布分支。
     * 这里是受控异常中断，不是 SIGKILL、网络分区或恢复时长测试。
     */
    @Test
    void interruptedPublicationResumesWithoutAddingStockAgain() {
        createActivity(1, 10, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5));
        initializer.initializeSafely(1L);
        recovery.freeze(1, "COMPONENT_SMOKE");
        faults.arm(com.voucherx.recovery.FaultPoint.RECOVERY_PUBLISHED, "1", "smoke-recovery",
                com.voucherx.recovery.FaultInjector.Action.FAIL);
        assertThatThrownBy(() -> recovery.recover(1))
                .isInstanceOf(com.voucherx.recovery.FaultInjector.InjectedFault.class);
        assertThat(recovery.status(1).state()).isEqualTo("RECONCILING");
        assertThat(recovery.recover(1).state()).isEqualTo("NORMAL");
        assertThat(redisTemplate.opsForValue().get(com.voucherx.common.redis.RedisKeys.seckillStock(1L)))
                .isEqualTo("10");
    }

    /**
     * 启动独立维护 CLI 子进程诊断一个已受理请求，验证正常退出、输出 WAIT 且原请求仍为 ACCEPTED，未写修复审计。
     * <p>
     * 仅在隔离组件环境验证诊断子命令；不是执行生产修复，也不是新的消息投递流程。
     *
     * @throws Exception
     *                       子进程启动、等待或临时输出文件操作失败
     */
    @Test
    void maintenanceCliReadsStateAndExitsWithoutMutatingIt() throws Exception {
        createUser(1);
        createActivity(1, 10, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5));
        initializer.initializeSafely(1L);
        var accepted = service.seckill(1L, 1L, "cli");
        var output = java.nio.file.Files.createTempFile("voucherx-maintenance-smoke-", ".log");
        var builder = new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "com.voucherx.maintenance.MaintenanceCli", "diagnose", accepted.requestId());
        builder.environment().putAll(java.util.Map.of("DB_URL", MYSQL.getJdbcUrl(), "DB_USERNAME", MYSQL.getUsername(),
                "DB_PASSWORD", MYSQL.getPassword(),
                "REDIS_HOST", REDIS.getHost(), "REDIS_PORT", String.valueOf(REDIS.getMappedPort(6379)),
                "RABBITMQ_HOST", RABBITMQ.getHost(), "RABBITMQ_PORT", String.valueOf(RABBITMQ.getAmqpPort()),
                "RABBITMQ_USERNAME", RABBITMQ.getAdminUsername(), "RABBITMQ_PASSWORD", RABBITMQ.getAdminPassword()));
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(java.nio.file.Files.readString(output)).isZero();
            assertThat(java.nio.file.Files.readString(output)).contains("Diagnosis[", "action=WAIT");
            assertThat(ledger.find(accepted.requestId()).phase()).isEqualTo("ACCEPTED");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_repair_audit", Integer.class)).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
}
