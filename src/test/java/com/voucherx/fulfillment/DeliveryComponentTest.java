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

@TestPropertySource(properties={"voucherx.delivery.enabled=false","voucherx.commerce.simulation-enabled=true"})
class DeliveryComponentTest extends AbstractIntegrationTest {
    @Autowired SeckillService service;
    @Autowired SeckillStockInitializer initializer;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RequestLedger ledger;
    @Autowired com.voucherx.commerce.CommerceService commerce;
    @Autowired com.voucherx.reconcile.CompensationService compensation;
    @Autowired com.voucherx.order.service.OrderPersistenceService persistence;
    @Autowired com.voucherx.reconcile.ReconciliationService reconciliation;
    @Test void realComponentsDeliverAcceptedRequestAndReplayIdempotently() {
        createUser(1);createActivity(1,10,10,LocalDateTime.now().minusMinutes(1),LocalDateTime.now().plusMinutes(5));
        assertThat(initializer.initializeSafely(1L)).isTrue();
        var accepted=service.seckill(1L,1L,"smoke-1");
        assertThat(ledger.find(accepted.requestId()).phase()).isEqualTo("ACCEPTED");
        assertThat(dispatcher.dispatch()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(ledger.find(accepted.requestId()).phase()).isEqualTo("SUCCEEDED"));
        assertThat(service.seckill(1L,1L,"smoke-1").requestId()).isEqualTo(accepted.requestId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_voucher_order",Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=1",Integer.class)).isEqualTo(9);
    }
    @Test void realPurchaseIssueCancelRelockAndRedeem() {
        createUser(1);createActivity(1,10,10,LocalDateTime.now().minusMinutes(1),LocalDateTime.now().plusMinutes(5));
        initializer.initializeSafely(1L);
        var accepted=service.seckill(1L,1L,"commerce");dispatcher.dispatch();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(ledger.find(accepted.requestId()).orderId()).isNotNull());
        long purchase=ledger.find(accepted.requestId()).orderId();
        var purchaseKind=com.voucherx.commerce.CommerceService.Kind.PURCHASE;
        var consumeKind=com.voucherx.commerce.CommerceService.Kind.CONSUMPTION;
        assertThat(commerce.simulate(1,purchaseKind,purchase,"pay-buy",100,true).outcome()).isEqualTo("APPLIED");
        assertThat(commerce.simulate(1,purchaseKind,purchase,"pay-buy",100,true).outcome()).isEqualTo("APPLIED");
        assertThat(commerce.coupons(1,0,20)).hasSize(1);
        long coupon=((Number)commerce.coupons(1,0,20).getFirst().get("id")).longValue();
        long cancelled=commerce.consume(1,1,coupon,2000);
        assertThat(commerce.cancel(1,consumeKind,cancelled)).isEqualTo("CANCELLED");
        long consume=commerce.consume(1,1,coupon,2000);
        assertThat(commerce.simulate(1,consumeKind,cancelled,"late-pay",1000,true).outcome()).isEqualTo("CONFLICT_TERMINAL");
        assertThat(commerce.simulate(1,consumeKind,consume,"pay-use",1000,true).outcome()).isEqualTo("APPLIED");
        assertThat(commerce.simulate(1,consumeKind,consume,"pay-use-again",1000,true).outcome()).isEqualTo("ALREADY_PAID");
        assertThat(commerce.coupon(coupon,false).get("state")).isEqualTo("USED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_coupon_action WHERE action='USE'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->commerce.cancel(1,consumeKind,consume)).isInstanceOf(com.voucherx.common.exception.BusinessException.class);
    }
    @Test void durableClosureAndRepeatedCompensationReturnStockOnce() {
        createUser(1);createActivity(1,10,10,LocalDateTime.now().minusMinutes(1),LocalDateTime.now().plusMinutes(5));
        initializer.initializeSafely(1L);
        var accepted=service.seckill(1L,1L,"compensate");
        var message=ledger.find(accepted.requestId()).message();
        compensation.prepare(accepted.requestId(),"SMOKE_CLOSED");
        assertThat(compensation.execute(accepted.requestId())).isEqualTo("RELEASED");
        assertThat(compensation.execute(accepted.requestId())).isEqualTo("RELEASED");
        assertThat(redisTemplate.opsForValue().get(com.voucherx.common.redis.RedisKeys.seckillStock(1L))).isEqualTo("10");
        assertThatThrownBy(()->persistence.persist(message)).isInstanceOf(com.voucherx.order.service.OrderBusinessException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_voucher_order",Integer.class)).isZero();
        assertThat(reconciliation.diagnose(accepted.requestId()).action()).isEqualTo(com.voucherx.reconcile.ReconcileDecision.WAIT);
    }
}
