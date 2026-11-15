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
 * 03 的发布器：Java 订单消息 → JSON 持久化 AMQP 消息 → Broker 发布确认分类。
 * <p>
 * 业务 messageId 固定等于 Outbox eventId；每次发送另建 CorrelationData ID，避免混淆重发与新业务。
 * application.yml 启用 correlated confirm、publisher returns 和 mandatory，本实现据此观察
 * ACK/NACK 及不可路由退回。
 * <p>
 * Confirm 是生产者收到的发布答复，basicAck 是消费者确认交付；实际购买单成功由 MySQL 事务确定。
 * 消费与发布回写可交错，不能依赖 SENT 必然早于 SUCCEEDED。
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
     * 先以本次独立 CorrelationData 构造并发送消息，再在配置时限内等待 Confirm。
     * <p>
     * 序列化失败抛 IllegalStateException；连接阶段 AmqpConnectException 映射 NOT_SENT，其他 AMQP
     * 发送异常映射 UNKNOWN。
     * 有确认后先判断 ACK，NACK 映射 NACKED；ACK 且存在 returned 信息映射 RETURNED，其余为 CONFIRMED。
     * 等待超时、执行异常或线程中断映射 UNKNOWN，中断时还恢复中断标记。
     * <p>
     * UNKNOWN 不能推断未入队，CONFIRMED 也不表示消费者已成功建单。这里只分类，不改请求/Outbox 或释放 Redis。
     * 发送器负责安排原事件重试；消息可能已经被消费而确认尚未被本线程处理。
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
     * 用 ObjectMapper 把 record 编码为 JSON 字节，设置 JSON/UTF-8 和 PERSISTENT 投递模式。
     * <p>
     * AMQP 属性 messageId 与 JSON 内 messageId 都使用固定 E1；投递次数由数据库 Outbox 记录。
     * 持久化属性只是消息发布配置，不替代 Broker 确认、订单提交或消费者 ACK。
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
