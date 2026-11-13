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

    private RequestRecord accepted() {
        createUser(1);
        createActivity(1, 10, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5));
        assertThat(initializer.initializeSafely(1L)).isTrue();
        return ledger.find(seckill.seckill(1L, 1L, "contract").requestId());
    }

    private long purchase() {
        return orders.persist(accepted().message()).orderId();
    }

    private long issuedCoupon() {
        long id = purchase();
        commerce.simulate(1, CommerceService.Kind.PURCHASE, id, "purchase-payment", 100, true);
        return commerce.coupons(1, 0, 20).getFirst().id();
    }

    private com.fasterxml.jackson.databind.JsonNode databaseJson(Object value) throws Exception {
        return json.readTree(json.writeValueAsString(value));
    }

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

    @Test
    void consumptionFailureRollsBackGeneratedOrderAndLockTogether() {
        long coupon = issuedCoupon();
        faults.arm(FaultPoint.COUPON_LOCKED, "*", "lock-rollback", FaultInjector.Action.FAIL);
        assertThatThrownBy(() -> commerce.consume(1, 1, coupon, 2000)).isInstanceOf(FaultInjector.InjectedFault.class);
        assertThat(commerce.coupon(coupon, false).state()).isEqualTo("AVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_consumption_order", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_coupon_action", Integer.class)).isZero();
    }

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
