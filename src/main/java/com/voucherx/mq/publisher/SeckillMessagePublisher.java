package com.voucherx.mq.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voucherx.config.SeckillProperties;
import com.voucherx.mq.config.RabbitMqConfig;
import com.voucherx.seckill.model.SeckillOrderMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 秒杀消息发布器。只有 Broker ACK 且 mandatory 消息成功路由时才返回 CONFIRMED。
 *
 * Spring AMQP 3.1.7 的 PublisherCallbackChannelImpl 会在处理 Confirm 前等待
 * mandatory return 回调完成（最多 60 秒），并把 ReturnedMessage 写入 CorrelationData。
 * 因此这里不需要再额外 sleep，也不会把 Return 等待误记为 Redis 耗时。
 */
@Component
public class SeckillMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(SeckillMessagePublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final SeckillProperties properties;

    public SeckillMessagePublisher(RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            SeckillProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        // 仅用于记录不可路由消息；CorrelationData.returned 由 Spring AMQP 维护。
        this.rabbitTemplate.setReturnsCallback(returned -> log.error("秒杀消息不可路由，exchange={}, routingKey={}, reply={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
    }

    public PublishResult publish(SeckillOrderMessage message) {
        return publishInternal(message, RabbitMqConfig.MAIN_EXCHANGE,
                RabbitMqConfig.MAIN_ROUTING_KEY, 0);
    }

    public PublishResult publishRetry(SeckillOrderMessage message, int retryCount) {
        return publishInternal(message, RabbitMqConfig.RETRY_EXCHANGE,
                RabbitMqConfig.RETRY_ROUTING_KEY, retryCount);
    }

    private PublishResult publishInternal(SeckillOrderMessage message, String exchange,
            String routingKey, int retryCount) {
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());

        Message amqpMessage;
        try {
            amqpMessage = buildMessage(message, retryCount);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("秒杀消息序列化失败", ex);
        }

        try {
            rabbitTemplate.send(exchange, routingKey, amqpMessage, correlationData);
        } catch (AmqpConnectException ex) {
            log.error("RabbitMQ 连接失败，消息确定未发送，requestId={}", message.requestId(), ex);
            return PublishResult.NOT_SENT;
        } catch (AmqpException ex) {
            log.error("RabbitMQ 发送异常，发布结果无法确定，requestId={}", message.requestId(), ex);
            return PublishResult.UNKNOWN;
        }

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    properties.publishConfirmTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                return PublishResult.NACKED;
            }
            if (correlationData.getReturned() != null) {
                return PublishResult.RETURNED;
            }
            return PublishResult.CONFIRMED;
        } catch (TimeoutException ex) {
            log.warn("等待 RabbitMQ 发布确认超时，requestId={}", message.requestId());
            return PublishResult.UNKNOWN;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("等待 RabbitMQ 发布确认被中断，requestId={}", message.requestId());
            return PublishResult.UNKNOWN;
        } catch (ExecutionException ex) {
            log.warn("等待 RabbitMQ 发布确认失败，requestId={}", message.requestId(), ex);
            return PublishResult.UNKNOWN;
        }
    }

    private Message buildMessage(SeckillOrderMessage payload, int retryCount)
            throws JsonProcessingException {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding("UTF-8");
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setMessageId(payload.messageId());
        messageProperties.setHeader("x-retry-count", retryCount);
        byte[] body = objectMapper.writeValueAsBytes(payload);
        return new Message(body, messageProperties);
    }
}
