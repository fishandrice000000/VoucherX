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
 * 共享真实组件回归：03 从受理与投递开始，04 继续付款用券，05/06 覆盖关闭补偿和受控恢复。
 * <p>
 * 真实 MySQL、Redis、RabbitMQ 来自隔离容器；关闭自动 Outbox 投递，正常消息流程由用例显式 dispatch 接续。
 * 03 先读 realComponentsDeliverAcceptedRequestAndReplayIdempotently；买券到核销再读
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
    com.voucherx.order.service.OrderPersistenceService persistence;
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
     * 04 的顺序真实组件链路：先通过 Outbox/MQ 建购买单，再付款发券、消费锁券、取消释放、重锁与付款核销。
     * <p>
     * 用户/活动均为 1，买券支付 100 分、券面额 1000 分、消费原额 2000 分、应付 1000 分，与指南金额不同但调用关系一致。
     * 相同 pay-buy 事件重放仍返回原 APPLIED 且只发一张券；取消旧消费单后，新单用新令牌锁券，旧单迟到成功只能得
     * CONFLICT_TERMINAL。
     * <p>
     * 新消费单付款 APPLIED，换新事件再成功得 ALREADY_PAID，最终券 USED 且只有一条 USE；已付单取消被拒绝。
     * 这些动作按顺序构造，不是穷尽付款/取消所有线程交错；并发锁券与回滚另看 PersistenceContractTest。
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
        var purchaseKind = com.voucherx.commerce.CommerceService.Kind.PURCHASE;
        var consumeKind = com.voucherx.commerce.CommerceService.Kind.CONSUMPTION;
        assertThat(commerce.simulate(1, purchaseKind, purchase, "pay-buy", 100, true).outcome()).isEqualTo("APPLIED");
        assertThat(commerce.simulate(1, purchaseKind, purchase, "pay-buy", 100, true).outcome()).isEqualTo("APPLIED");
        assertThat(commerce.coupons(1, 0, 20)).hasSize(1);
        long coupon = commerce.coupons(1, 0, 20).getFirst().id();
        long cancelled = commerce.consume(1, 1, coupon, 2000);
        assertThat(commerce.cancel(1, consumeKind, cancelled)).isEqualTo("CANCELLED");
        long consume = commerce.consume(1, 1, coupon, 2000);
        assertThat(commerce.simulate(1, consumeKind, cancelled, "late-pay", 1000, true).outcome())
                .isEqualTo("CONFLICT_TERMINAL");
        assertThat(commerce.simulate(1, consumeKind, consume, "pay-use", 1000, true).outcome()).isEqualTo("APPLIED");
        assertThat(commerce.simulate(1, consumeKind, consume, "pay-use-again", 1000, true).outcome())
                .isEqualTo("ALREADY_PAID");
        assertThat(commerce.coupon(coupon, false).state()).isEqualTo("USED");
        assertThat(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_coupon_action WHERE action='USE'", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> commerce.cancel(1, consumeKind, consume))
                .isInstanceOf(com.voucherx.common.exception.BusinessException.class);
    }

    /**
     * 受理后先提交关闭和补偿意图，再执行两次补偿，Redis 库存应只返还一次到 10。
     * <p>
     * 随后尝试迟到的原消息不能建单，说明业务关闭屏障不同于停止某一次投递；补偿实现沿 05 阅读。
     */
    @Test
    void durableClosureAndRepeatedCompensationReturnStockOnce() {
        createUser(1);
        createActivity(1, 10, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5));
        initializer.initializeSafely(1L);
        var accepted = service.seckill(1L, 1L, "compensate");
        var message = ledger.find(accepted.requestId()).message();
        compensation.prepare(accepted.requestId(), "SMOKE_CLOSED");
        assertThat(compensation.execute(accepted.requestId())).isEqualTo("RELEASED");
        assertThat(compensation.execute(accepted.requestId())).isEqualTo("RELEASED");
        assertThat(redisTemplate.opsForValue().get(com.voucherx.common.redis.RedisKeys.seckillStock(1L)))
                .isEqualTo("10");
        assertThatThrownBy(() -> persistence.persist(message))
                .isInstanceOf(com.voucherx.order.service.OrderBusinessException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isZero();
        assertThat(reconciliation.diagnose(accepted.requestId()).action())
                .isEqualTo(com.voucherx.reconcile.ReconcileDecision.WAIT);
    }

    /**
     * 受理请求后冻结活动并删除其 Redis 证据，受控恢复仍需保留该请求占用，Redis 可用为 9。
     * <p>
     * 旧代次消息不能建单，也不能把当前请求改为失败；手动投递恢复后的原请求，最终正常建单且 MySQL 库存 9。
     * 这是 06 的恢复分支，与普通租约重试是不同机制。
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
        assertThatThrownBy(() -> persistence.persist(old))
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
     * 在恢复发布 Redis 状态后注入中断，活动停在 RECONCILING；再次恢复回到 NORMAL，库存仍为 10，验证恢复重入不重复加量。
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
