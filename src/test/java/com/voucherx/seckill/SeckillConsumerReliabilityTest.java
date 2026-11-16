package com.voucherx.seckill;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.mq.config.RabbitMqConfig;
import com.voucherx.order.service.PurchaseOrderCreationService;
import com.voucherx.seckill.dto.SeckillAcceptResponse;
import com.voucherx.seckill.model.SeckillRequestStatus;
import com.voucherx.seckill.service.SeckillRequestStatusCache;
import com.voucherx.seckill.service.SeckillService;
import com.voucherx.seckill.service.SeckillStockService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 验证失败消息不静默丢失：临时失败会重试，解析失败进入死信队列。
 */
class SeckillConsumerReliabilityTest extends AbstractIntegrationTest {

    private static final long VOUCHER_ID = 3001L;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillStockService stockService;
    @Autowired
    private SeckillRequestStatusCache statusStore;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @SpyBean
    private PurchaseOrderCreationService orderPersistenceService;

    @Test
    void transientFailureIsRetriedAfterDelayThenSucceeds() {
        createUser(1);
        createActivity(VOUCHER_ID, 1, 1, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 1);

        doThrow(new DataAccessResourceFailureException("模拟数据库瞬时失败"))
                .doCallRealMethod()
                .when(orderPersistenceService).createPurchaseOrder(any());

        SeckillAcceptResponse accepted = seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(redisTemplate.<String, String>opsForHash()
                    .entries(com.voucherx.common.redis.RedisKeys.seckillRequest(accepted.requestId())).get("status"))
                    .isEqualTo(SeckillRequestStatus.SUCCESS.name());
            Integer orders = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?",
                    Integer.class, VOUCHER_ID);
            assertThat(orders).isEqualTo(1);
        });

        verify(orderPersistenceService, times(2)).createPurchaseOrder(any());
        assertThat(stockService.stock(VOUCHER_ID)).isZero();
    }

    @Test
    void malformedMessageIsIsolatedInDeadLetterQueue() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(UUID.randomUUID().toString());
        Message malformed = new Message("这不是合法 JSON".getBytes(StandardCharsets.UTF_8), properties);

        rabbitTemplate.send(RabbitMqConfig.MAIN_EXCHANGE, RabbitMqConfig.MAIN_ROUTING_KEY, malformed);
        Message dead = rabbitTemplate.receive(RabbitMqConfig.DEAD_QUEUE, 5000);

        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("这不是合法 JSON");
    }
}
