package com.voucherx.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voucherx.AbstractIntegrationTest;
import com.voucherx.commerce.CommerceService;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.fulfillment.mapper.OutboxMapper;
import com.voucherx.order.service.OrderPersistenceService;
import com.voucherx.recovery.FaultInjector;
import com.voucherx.recovery.FaultPoint;
import com.voucherx.seckill.service.SeckillService;
import com.voucherx.seckill.service.SeckillStockInitializer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入门指南的持久化契约对照：验证类型化响应、并发锁券、交易回滚和 Outbox 租约保护。
 * <p>
 * 测试直接调用服务并用独立数据库查询断言，适合与 Mapper/XML 逐步比对；真实 MQ 交接另看
 * {@link DeliveryComponentTest#realPurchaseIssueCancelRelockAndRedeem()}。
 */
@TestPropertySource(properties = { "voucherx.delivery.enabled=false", "voucherx.commerce.simulation-enabled=true",
        "voucherx.fault.enabled=true" })
class PersistenceContractTest extends AbstractIntegrationTest {
    @Autowired
    SeckillService seckill;
    @Autowired
    SeckillStockInitializer initializer;
    @Autowired
    RequestLedger ledger;
    @Autowired
    OrderPersistenceService orders;
    @Autowired
    CommerceService commerce;
    @Autowired
    OutboxMapper outbox;
    @Autowired
    FaultInjector faults;
    @Autowired
    ObjectMapper json;

    @AfterEach
    void clearFaults() {
        faults.clear();
    }

    /** 创建用户及活动 1，安全初始化 Redis，再受理一次抢券，返回尚未手动建单的流水。 */
    private RequestRecord accepted() {
        createUser(1);
        createActivity(1, 10, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5));
        assertThat(initializer.initializeSafely(1L)).isTrue();
        return ledger.find(seckill.seckill(1L, 1L, "contract").requestId());
    }

    /** 用持久化消息直接调用事务建单服务，取得待付款购买订单 ID。 */
    private long purchase() {
        return orders.persist(accepted().message()).orderId();
    }

    /** 为购买单模拟成功支付 100 分，返回该用户获得的第一张用户券 ID。 */
    private long issuedCoupon() {
        long id = purchase();
        commerce.simulate(1, CommerceService.Kind.PURCHASE, id, "purchase-payment", 100, true);
        return commerce.coupons(1, 0, 20).getFirst().id();
    }

    /** 将实体或数据库结果转为 JSON 树，便于忽略对象类型差异、精确比较字段和值。 */
    private com.fasterxml.jackson.databind.JsonNode databaseJson(Object value) throws Exception {
        return json.readTree(json.writeValueAsString(value));
    }

    /** 验证购买单、用户券和消费单的类型化 HTTP 响应仍与原数据库 Map JSON 契约一致。 */
    @Test
    void typedResponsesPreserveDatabaseMapJsonContract() throws Exception {
        long id = purchase();
        redisTemplate.opsForValue().set(RedisKeys.token("contract-token"), "1");
        var response = mockMvc.perform(get("/api/orders/PURCHASE/{id}", id)
                .header("Authorization", "Bearer contract-token")).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).get("data"))
                .isEqualTo(databaseJson(jdbcTemplate.queryForMap("SELECT * FROM tb_voucher_order WHERE id=?", id)));
        commerce.simulate(1, CommerceService.Kind.PURCHASE, id, "pay", 100, true);
        var coupons = commerce.coupons(1, 0, 20);
        long coupon = coupons.getFirst().id();
        var couponResponse = mockMvc.perform(get("/api/coupons").header("Authorization", "Bearer contract-token"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(couponResponse.getResponse().getContentAsString()).get("data"))
                .isEqualTo(databaseJson(
                        jdbcTemplate.queryForList("SELECT * FROM tb_user_coupon WHERE user_id=1 ORDER BY id")));
        long consumption = commerce.consume(1, 1, coupon, 2000);
        var consumptionResponse = mockMvc.perform(get("/api/orders/CONSUMPTION/{id}", consumption)
                .header("Authorization", "Bearer contract-token")).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(consumptionResponse.getResponse().getContentAsString()).get("data"))
                .isEqualTo(databaseJson(
                        jdbcTemplate.queryForMap("SELECT * FROM tb_consumption_order WHERE id=?", consumption)));
    }

    /** 两线程同时使用同一张券，最终只提交一个消费单和一条 LOCK 动作，券锁归属指向成功订单。 */
    @Test
    void concurrentConsumptionCommitsOnlyOneOrderAndCouponLock() throws Exception {
        long coupon = issuedCoupon();
        var start = new CyclicBarrier(2);
        Callable<Boolean> attempt = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                commerce.consume(1, 1, coupon, 2000);
                return true;
            } catch (BusinessException ex) {
                return false;
            }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(attempt);
            var second = workers.submit(attempt);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_consumption_order", Integer.class))
                .isEqualTo(1);
        assertThat(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_coupon_action WHERE action='LOCK'", Integer.class))
                .isEqualTo(1);
        assertThat(commerce.coupon(coupon, false).lockOrderId()).isEqualTo(
                jdbcTemplate.queryForObject("SELECT id FROM tb_consumption_order", Long.class));
    }

    /** 购买付款提交前注入异常，验证发券、支付事件和订单 PAID 状态一起回滚。 */
    @Test
    void paymentFailureRollsBackCouponEventAndOrderStateTogether() {
        long id = purchase();
        faults.arm(FaultPoint.PAYMENT_BEFORE_COMMIT, String.valueOf(id), "payment-rollback", FaultInjector.Action.FAIL);
        assertThatThrownBy(() -> commerce.simulate(1, CommerceService.Kind.PURCHASE, id, "failed-tx", 100, true))
                .isInstanceOf(FaultInjector.InjectedFault.class);
        assertThat(commerce.order(CommerceService.Kind.PURCHASE, id, 1, false).paymentStatus())
                .isEqualTo("PENDING_PAYMENT");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_user_coupon", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_payment_event", Integer.class)).isZero();
    }

    /** 锁券后注入异常，验证自增消费单、券锁和 LOCK 动作一起回滚，券仍可用。 */
    @Test
    void consumptionFailureRollsBackGeneratedOrderAndLockTogether() {
        long coupon = issuedCoupon();
        faults.arm(FaultPoint.COUPON_LOCKED, "*", "lock-rollback", FaultInjector.Action.FAIL);
        assertThatThrownBy(() -> commerce.consume(1, 1, coupon, 2000)).isInstanceOf(FaultInjector.InjectedFault.class);
        assertThat(commerce.coupon(coupon, false).state()).isEqualTo("AVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_consumption_order", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_coupon_action", Integer.class)).isZero();
    }

    /** 事件租约过期被新令牌接管后，旧令牌不能回写 SENT，新持有者才有回写资格。 */
    @Test
    void expiredOutboxLeaseCannotBeCompletedByPreviousClaimant() {
        var request = accepted();
        long now = System.currentTimeMillis();
        assertThat(outbox.claim(request.eventId(), "old", now + 1000, now)).isEqualTo(1);
        assertThat(outbox.claim(request.eventId(), "new", now + 2000, now)).isZero();
        assertThat(outbox.claim(request.eventId(), "new", now + 3000, now + 1001)).isEqualTo(1);
        assertThat(outbox.finish(request.eventId(), "old", "SENT", "OLD", now)).isZero();
        assertThat(outbox.finish(request.eventId(), "new", "SENT", "CONFIRMED", now)).isEqualTo(1);
    }
}
