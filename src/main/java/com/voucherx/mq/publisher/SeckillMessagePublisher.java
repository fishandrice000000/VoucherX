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
 * 把订单消息编码为持久化 AMQP 消息，并等待本次发布的 Broker 确认。
 * <p>
 * 固定 messageId 来自 Outbox 的 eventId；每次发送另有独立 CorrelationData ID 区分发布尝试。
 * 确认及路由成功只证明发布阶段完成，建单成功要由消费者事务和 MySQL 流水确认。
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

    /**
     * 发布到主交换机和主路由键，供 Outbox 投递器调用。
     *
     * @param message
     *                    由请求账本生成、保持固定身份的业务消息
     *
     * @return 本次发布和路由的确认结果
     */
    public PublishResult publish(SeckillOrderMessage message) {
        return publishInternal(message, RabbitMqConfig.MAIN_EXCHANGE,
                RabbitMqConfig.MAIN_ROUTING_KEY, 0);
    }

    /**
     * 发布到配置了 TTL 的重试队列；当前消费者异常的主要重试入口是数据库 Outbox.defer。
     *
     * @param message
     *                       保持原业务身份的消息
     * @param retryCount
     *                       写入消息头的重试次数
     *
     * @return 本次发布确认结果
     */
    public PublishResult publishRetry(SeckillOrderMessage message, int retryCount) {
        return publishInternal(message, RabbitMqConfig.RETRY_EXCHANGE,
                RabbitMqConfig.RETRY_ROUTING_KEY, retryCount);
    }

    /**
     * 序列化后发送，再等待配置时限内的 Broker Confirm，并检查 mandatory 退回结果。
     * <p>
     * 连接阶段失败为 NOT_SENT；Broker 拒绝为 NACKED；无法路由为 RETURNED；
     * 超时、中断或其他发送异常为 UNKNOWN。UNKNOWN 不能推断消息未入队，后续重复发送须依靠业务幂等。
     * 本方法不更改 MySQL 请求、Outbox 状态或 Redis 库存。
     *
     * @param message
     *                       业务消息
     * @param exchange
     *                       本次发布目标交换机
     * @param routingKey
     *                       路由键
     * @param retryCount
     *                       消息头中的重试次数
     *
     * @return 仅描述本次发送结果的枚举
     */
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

    /**
     * 设置 JSON/UTF-8、持久化投递模式、固定业务消息 ID 和重试头，再序列化负载。
     *
     * @throws JsonProcessingException
     *                                     业务消息无法编码为 JSON
     *
     * @param payload
     *                       订单业务负载
     * @param retryCount
     *                       供诊断的重试次数
     *
     * @return 发给 RabbitTemplate 的 AMQP 消息
     */
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
