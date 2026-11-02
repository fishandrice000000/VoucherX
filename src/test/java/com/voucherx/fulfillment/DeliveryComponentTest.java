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

@TestPropertySource(properties="voucherx.delivery.enabled=false")
class DeliveryComponentTest extends AbstractIntegrationTest {
    @Autowired SeckillService service;
    @Autowired SeckillStockInitializer initializer;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RequestLedger ledger;
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
}
