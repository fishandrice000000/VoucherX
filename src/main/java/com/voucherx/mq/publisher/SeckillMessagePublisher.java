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
 * 将业务订单消息编码为持久化 AMQP 消息，并返回 Broker 发布确认分类。
 * <p>
 * messageId 使用固定 Outbox eventId，每次发送另建 CorrelationData ID 关联此次确认。
 * 应用启用 correlated confirm、publisher returns 和 mandatory，以观察 ACK、NACK 和不可路由退回。
 * <p>
 * 发布器与消费者独立运行：发送器记录确认结果，消费者在 MySQL 建单事务提交后确认交付。
 *
 * @see com.voucherx.fulfillment.OutboxDispatcher
 * @see PublishResult
 * @see com.voucherx.mq.consumer.SeckillOrderConsumer
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
     * @return 本次发布和路由的确认结果
     */
    public PublishResult publish(SeckillOrderMessage message) {
        return publishInternal(message, RabbitMqConfig.MAIN_EXCHANGE,
                RabbitMqConfig.MAIN_ROUTING_KEY);
    }

    /**
     * 发送带独立 CorrelationData 的 AMQP 消息，并在配置时限内等待 Confirm。
     * <p>
     * 序列化失败抛 IllegalStateException；连接阶段失败映射 NOT_SENT，其他 AMQP 异常映射 UNKNOWN。
     * 确认 NACK 映射 NACKED，ACK 且 returned 映射 RETURNED，其余 ACK 映射 CONFIRMED。
     * 等待超时、执行异常或中断映射 UNKNOWN，中断时恢复线程标记。
     * <p>
     * Outbox 按该分类安排后续进度；UNKNOWN 的重发可能形成重复交付，由完整业务身份及消费幂等处理。
     *
     * @param message
     *                       固定业务身份的订单消息
     * @param exchange
     *                       本次目标交换机
     * @param routingKey
     *                       本次目标路由键
     * @return 本次发布尝试的结果分类
     * @throws IllegalStateException
     *                                   无法把消息编码为 JSON
     */
    private PublishResult publishInternal(SeckillOrderMessage message, String exchange,
            String routingKey) {
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());

        Message amqpMessage;
        try {
            amqpMessage = buildMessage(message);
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
     * 将业务消息编码为 JSON 字节，设置 UTF-8、PERSISTENT 投递模式和固定业务 messageId。
     * <p>
     * AMQP messageId 与 JSON messageId 均对应同一 Outbox 事件，投递尝试次数保存在数据库事件记录中。
     *
     * @param payload
     *                    由持久化请求生成的负载
     * @return 尚未发送的 AMQP 消息对象
     * @throws JsonProcessingException
     *                                     消息无法编码为 JSON
     */
    private Message buildMessage(SeckillOrderMessage payload)
            throws JsonProcessingException {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding("UTF-8");
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setMessageId(payload.messageId());
        byte[] body = objectMapper.writeValueAsBytes(payload);
        return new Message(body, messageProperties);
    }
}
