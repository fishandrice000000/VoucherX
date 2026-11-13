package com.voucherx.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.voucherx.admission.CompletionMetrics;
import com.voucherx.fulfillment.DeliveryProperties;
import com.voucherx.fulfillment.RequestLedger;
import com.voucherx.fulfillment.StaleDeliveryException;
import com.voucherx.mq.config.RabbitMqConfig;
import com.voucherx.order.service.OrderBusinessException;
import com.voucherx.order.service.OrderPersistResult;
import com.voucherx.order.service.OrderPersistenceService;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.seckill.service.SeckillRequestStatusStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 主队列的消费者：反序列化并校验消息，再调用事务服务创建购买订单。
 * <p>
 * MQ 线程没有 HTTP 的 UserContext，用户身份来自消息并由账本守卫核对。
 * 订单服务经过 Spring 代理提交后才 ACK；提交后、ACK 前发生故障会重投，重复投递复用原订单。
 * 默认逐条处理，可配置小批次共用事务；这里不是付款或发券入口。
 */
@Component
public class SeckillOrderConsumer {
    private static final Logger log = LoggerFactory.getLogger(SeckillOrderConsumer.class);
    private final com.voucherx.recovery.FaultInjector faults;
    private final ObjectMapper mapper;
    private final OrderPersistenceService orders;
    private final RequestLedger ledger;
    private final SeckillRequestStatusStore statuses;
    private final CompletionMetrics metrics;
    private final DeliveryProperties config;

    public SeckillOrderConsumer(ObjectMapper mapper, OrderPersistenceService orders, RequestLedger ledger,
            SeckillRequestStatusStore statuses, CompletionMetrics metrics, DeliveryProperties config,
            com.voucherx.recovery.FaultInjector faults) {
        this.faults = faults;
        this.mapper = mapper;
        this.orders = orders;
        this.ledger = ledger;
        this.statuses = statuses;
        this.metrics = metrics;
        this.config = config;
    }

    /**
     * 把 AMQP 投递信封和业务负载配对；前者携带 ACK 标签，后者携带业务身份。
     *
     * @param delivery
     *                     RabbitMQ 投递信封
     * @param message
     *                     已解析的业务消息
     */
    private record Item(Message delivery, SeckillOrderMessage message) {
    }

    /**
     * 校验 JSON、身份字段和单条大小，按数量及累计字节数拆成有界批次。
     * <p>
     * 畸形或超大消息直接 NACK 且不原队列重排；有效消息交给 process。
     * 主队列配置的死信绑定负责接收拒绝的投递。
     *
     * @throws IOException
     *                         向 RabbitMQ 写 ACK/NACK 失败
     *
     * @param deliveries
     *                       容器本次交付的消息列表
     * @param channel
     *                       本次投递所属通道，ACK 标签只在该通道有意义
     */
    @RabbitListener(queues = RabbitMqConfig.MAIN_QUEUE, containerFactory = "deliveryContainerFactory")
    public void onMessages(List<Message> deliveries, Channel channel) throws IOException {
        List<Item> batch = new ArrayList<>();
        int bytes = 0;
        for (Message delivery : deliveries) {
            SeckillOrderMessage message;
            try {
                message = mapper.readValue(delivery.getBody(), SeckillOrderMessage.class);
                if (message == null || message.requestId() == null || message.messageId() == null
                        || message.voucherId() == null
                        || message.userId() == null || message.userId() < 1 || message.voucherId() < 1
                        || message.epoch() < 1)
                    throw new IllegalArgumentException("消息字段不完整");
            } catch (Exception ex) {
                channel.basicNack(tag(delivery), false, false);
                continue;
            }
            if (delivery.getBody().length > config.batchBytes()) {
                channel.basicNack(tag(delivery), false, false);
                continue;
            }
            if (!batch.isEmpty() && (batch.size() >= config.batchSize()
                    || bytes + delivery.getBody().length > config.batchBytes())) {
                process(batch, channel);
                batch.clear();
                bytes = 0;
            }
            batch.add(new Item(delivery, message));
            bytes += delivery.getBody().length;
        }
        if (!batch.isEmpty())
            process(batch, channel);
    }

    /**
     * 单条调用的适配入口，复用相同的校验和处理规则。
     *
     * @throws IOException
     *                         消息确认失败
     *
     * @param message
     *                    AMQP 投递
     * @param channel
     *                    对应的 RabbitMQ 通道
     */
    public void onMessage(Message message, Channel channel) throws IOException {
        onMessages(List.of(message), channel);
    }

    /**
     * 开启批处理且有多条消息时，先尝试一个数据库事务处理整个批次；代理正常返回后逐条确认。
     * <p>
     * 批次事务失败会整体回滚，此时尚未 ACK，再有界地逐条处理一次，隔离导致失败的消息。
     * 默认未开启批处理时直接进入 one。
     *
     * @throws IOException
     *                         消息确认失败
     *
     * @param batch
     *                    已校验的有界消息批次
     * @param channel
     *                    本次消费通道
     */
    private void process(List<Item> batch, Channel channel) throws IOException {
        if (config.batchEnabled() && batch.size() > 1) {
            List<OrderPersistResult> results;
            long started = metrics.begin();
            try {
                results = orders.persistBatch(batch.stream().map(Item::message).toList());
            } catch (Exception ex) {
                metrics.recordFailure(started);
                results = null;
            } finally {
                metrics.end();
            }
            if (results != null) {
                faults.hit(com.voucherx.recovery.FaultPoint.BATCH_AFTER_COMMIT_BEFORE_ACK,
                        batch.getFirst().message().requestId(), batch.getFirst().message().messageId());
                for (int i = 0; i < batch.size(); i++) {
                    if (results.get(i).created())
                        metrics.recordCreated(started);
                    else
                        metrics.recordDuplicate(started);
                    success(batch.get(i), results.get(i), channel);
                }
                return;
            }
            // 代理已回滚整个批次，尚无 ACK；有界降级到逐条，以隔离毒消息。
        }
        for (Item item : batch)
            one(item, channel);
    }

    /**
     * 单条建单：调用 orders.persist，经事务代理提交后更新状态缓存并 ACK。
     * <p>
     * 旧代次投递直接 ACK；确定业务拒绝先由账本核验并关闭，允许时写失败缓存并 ACK。
     * 其他异常通过账本安排 Outbox 重试，再 NACK 原投递且不原队列重排。
     * 身份冲突不能关闭真实请求或覆盖其缓存；各数据库异常处理与已回滚的建单事务分开。
     *
     * @throws IOException
     *                         消息确认失败
     *
     * @param item
     *                    本次投递及其业务负载
     * @param channel
     *                    本次消费通道
     */
    private void one(Item item, Channel channel) throws IOException {
        long started = metrics.begin();
        OrderPersistResult result;
        try {
            result = orders.persist(item.message());
        } catch (StaleDeliveryException ex) {
            channel.basicAck(tag(item.delivery()), false);
            return;
        } catch (OrderBusinessException ex) {
            metrics.recordBusinessFailure(started);
            // 身份冲突不能关闭真实请求，也不能覆盖其状态缓存。
            if (ledger.fail(item.message(), ex.failure().name())) {
                statuses.markFailed(item.message().requestId(), item.message().messageId(), ex.failure().name());
                channel.basicAck(tag(item.delivery()), false);
            } else
                channel.basicNack(tag(item.delivery()), false, false);
            return;
        } catch (Exception ex) {
            metrics.recordFailure(started);
            log.warn("下单失败，按持久化事件安排重试，requestId={}", item.message().requestId(), ex);
            ledger.defer(item.message(), ex.getClass().getSimpleName());
            channel.basicNack(tag(item.delivery()), false, false);
            return;
        } finally {
            metrics.end();
        }
        faults.hit(com.voucherx.recovery.FaultPoint.ORDER_AFTER_COMMIT_BEFORE_ACK, item.message().requestId(),
                item.message().messageId());
        if (result.created())
            metrics.recordCreated(started);
        else
            metrics.recordDuplicate(started);
        success(item, result, channel);
    }

    /**
     * 事务已提交后尽力更新 Redis SUCCESS 缓存，再单条 ACK；Redis 数据访问异常被状态存储层记录，不回滚数据库。
     *
     * @throws IOException
     *                         ACK 发送失败，消息可能重投
     *
     * @param item
     *                    已成功处理的投递
     * @param result
     *                    已提交的新建或复用订单结果
     * @param channel
     *                    原投递所属通道
     */
    private void success(Item item, OrderPersistResult result, Channel channel) throws IOException {
        statuses.markSuccess(item.message().requestId(), item.message().messageId(), result.orderId());
        channel.basicAck(tag(item.delivery()), false);
    }

    /**
     * 从投递属性取当前通道内的确认标签；它与业务 requestId、messageId 是不同的标识。
     *
     * @param m
     *              AMQP 投递信封
     *
     * @return 通道内的 deliveryTag
     */
    private long tag(Message m) {
        return m.getMessageProperties().getDeliveryTag();
    }
}
