package com.voucherx.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.voucherx.config.MqProperties;
import com.voucherx.mq.config.RabbitMqConfig;
import com.voucherx.mq.publisher.PublishResult;
import com.voucherx.mq.publisher.SeckillMessagePublisher;
import com.voucherx.order.entity.VoucherOrder;
import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.order.service.OrderBusinessException;
import com.voucherx.order.service.OrderPersistResult;
import com.voucherx.order.service.OrderPersistenceService;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.seckill.service.SeckillRequestStatusStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 消息消费者只负责：解析消息、调用事务服务、决定 ACK / 重试 / 死信。
 * 订单业务逻辑在 OrderPersistenceService 中。
 */
@Component
public class SeckillOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderConsumer.class);
    private static final String RETRY_COUNT_HEADER = "x-retry-count";

    private final ObjectMapper objectMapper;
    private final OrderPersistenceService orderPersistenceService;
    private final VoucherOrderMapper orderMapper;
    private final SeckillRequestStatusStore statusStore;
    private final SeckillMessagePublisher publisher;
    private final MqProperties mqProperties;

    public SeckillOrderConsumer(ObjectMapper objectMapper,
                                OrderPersistenceService orderPersistenceService,
                                VoucherOrderMapper orderMapper,
                                SeckillRequestStatusStore statusStore,
                                SeckillMessagePublisher publisher,
                                MqProperties mqProperties) {
        this.objectMapper = objectMapper;
        this.orderPersistenceService = orderPersistenceService;
        this.orderMapper = orderMapper;
        this.statusStore = statusStore;
        this.publisher = publisher;
        this.mqProperties = mqProperties;
    }

    @RabbitListener(queues = RabbitMqConfig.MAIN_QUEUE)
    public void onMessage(Message amqpMessage, Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        SeckillOrderMessage message;
        try {
            message = objectMapper.readValue(amqpMessage.getBody(), SeckillOrderMessage.class);
            validate(message);
        } catch (Exception ex) {
            log.warn("收到无法解析的秒杀消息，转入死信队列: {}", ex.getMessage());
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        int retryCount = readRetryCount(amqpMessage);
        try {
            OrderPersistResult result = orderPersistenceService.persist(message);
            statusStore.markSuccess(message.requestId(), message.messageId(), result.orderId());
            log.debug("秒杀订单处理成功，requestId={}, messageId={}, orderId={}, created={}",
                    message.requestId(), message.messageId(), result.orderId(), result.created());
            channel.basicAck(deliveryTag, false);
        } catch (OrderBusinessException ex) {
            statusStore.markFailed(message.requestId(), message.messageId(), ex.failure().name());
            log.warn("秒杀订单业务失败，不再重试，requestId={}, messageId={}, reason={}",
                    message.requestId(), message.messageId(), ex.failure());
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException ex) {
            handleDuplicate(message, deliveryTag, retryCount, channel, ex);
        } catch (Exception ex) {
            handleTransient(message, deliveryTag, retryCount, channel, ex);
        }
    }

    /**
     * 唯一约束冲突时，事务已经回滚。重新查询数据库判断是重复消息还是并发冲突：
     * 已有同一 requestId 的订单 -> 幂等成功；已有同一用户订单 -> 业务失败；否则重试。
     */
    private void handleDuplicate(SeckillOrderMessage message, long deliveryTag, int retryCount,
                                 Channel channel, DuplicateKeyException cause) throws IOException {
        try {
            VoucherOrder existing = orderMapper.selectByRequestId(message.requestId());
            if (existing != null) {
                statusStore.markSuccess(message.requestId(), message.messageId(), existing.getId());
                log.info("检测到重复消息，订单已存在，requestId={}, orderId={}",
                        message.requestId(), existing.getId());
                channel.basicAck(deliveryTag, false);
                return;
            }
            VoucherOrder byUser = orderMapper.selectByVoucherAndUser(message.voucherId(), message.userId());
            if (byUser != null) {
                statusStore.markFailed(message.requestId(), message.messageId(),
                        "DUPLICATE_USER");
                log.warn("用户已存在秒杀订单，requestId={}, existingOrderId={}",
                        message.requestId(), byUser.getId());
                channel.basicAck(deliveryTag, false);
                return;
            }
        } catch (Exception queryEx) {
            handleTransient(message, deliveryTag, retryCount, channel, queryEx);
            return;
        }
        handleTransient(message, deliveryTag, retryCount, channel, cause);
    }

    private void handleTransient(SeckillOrderMessage message, long deliveryTag, int retryCount,
                                 Channel channel, Exception cause) throws IOException {
        if (retryCount >= mqProperties.maxRetries()) {
            statusStore.markFailed(message.requestId(), message.messageId(), "RETRY_EXHAUSTED");
            log.error("秒杀消息重试次数耗尽，转入死信队列，requestId={}, retryCount={}",
                    message.requestId(), retryCount, cause);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        PublishResult result;
        try {
            result = publisher.publishRetry(message, retryCount + 1);
        } catch (Exception ex) {
            log.error("发布重试消息异常，原消息转入死信队列，requestId={}", message.requestId(), ex);
            statusStore.markUnknown(message.requestId(), message.messageId(), "RETRY_PUBLISH_UNKNOWN");
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        if (result == PublishResult.CONFIRMED) {
            log.warn("秒杀消息处理失败，已安排第 {} 次重试，requestId={}, cause={}",
                    retryCount + 1, message.requestId(), cause.toString());
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (result == PublishResult.UNKNOWN) {
            // 重试消息可能已经进入队列，不能断言最终失败；保留 UNKNOWN 并隔离原消息。
            statusStore.markUnknown(message.requestId(), message.messageId(), "RETRY_PUBLISH_UNKNOWN");
            log.error("重试消息发布结果未知，原消息转入死信队列，requestId={}", message.requestId());
        } else {
            statusStore.markFailed(message.requestId(), message.messageId(), "RETRY_PUBLISH_FAILED");
            log.error("重试消息确定发布失败，原消息转入死信队列，requestId={}, result={}",
                    message.requestId(), result);
        }
        channel.basicNack(deliveryTag, false, false);
    }

    private int readRetryCount(Message message) {
        Object value = message.getMessageProperties().getHeader(RETRY_COUNT_HEADER);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void validate(SeckillOrderMessage message) {
        if (message == null
                || message.messageId() == null || message.messageId().isBlank()
                || message.requestId() == null || message.requestId().isBlank()
                || message.userId() == null || message.voucherId() == null) {
            throw new IllegalArgumentException("秒杀消息字段不完整");
        }
    }
}
