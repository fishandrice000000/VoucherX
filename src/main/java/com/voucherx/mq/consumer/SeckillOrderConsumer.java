package com.voucherx.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.voucherx.admission.CompletionMetrics;
import com.voucherx.fulfillment.DeliveryProperties;
import com.voucherx.fulfillment.RequestLedger;
import com.voucherx.fulfillment.StaleDeliveryException;
import com.voucherx.mq.config.RabbitMqConfig;
import com.voucherx.order.service.OrderBusinessException;
import com.voucherx.order.service.PurchaseOrderCreationResult;
import com.voucherx.order.service.PurchaseOrderCreationService;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.seckill.service.SeckillRequestStatusCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 异步建单消费者：校验 AMQP JSON，调用事务服务创建购买订单，再确认本通道的交付。
 * <p>
 * 请求账本守卫核对消息中的用户、活动及完整请求身份。默认逐条事务，可选有界批事务。
 * 提交后 ACK 前中断产生的重复交付按固定 requestId 复用原订单。
 * <p>
 * 暂时性失败由账本安排 Outbox 重试，当前交付进入死信；业务失败通过请求关闭记录终态，
 * 失效交付按守卫结果直接确认。
 *
 * @see com.voucherx.order.service.PurchaseOrderCreationService
 * @see com.voucherx.fulfillment.RequestLedger
 * @see com.voucherx.fulfillment.DeliveryListenerConfig
 */
@Component
public class SeckillOrderConsumer {
    private static final Logger log = LoggerFactory.getLogger(SeckillOrderConsumer.class);
    private final com.voucherx.recovery.FaultInjector faults;
    private final ObjectMapper mapper;
    private final PurchaseOrderCreationService orders;
    private final RequestLedger ledger;
    private final SeckillRequestStatusCache statuses;
    private final CompletionMetrics metrics;
    private final DeliveryProperties config;

    public SeckillOrderConsumer(ObjectMapper mapper, PurchaseOrderCreationService orders, RequestLedger ledger,
            SeckillRequestStatusCache statuses, CompletionMetrics metrics, DeliveryProperties config,
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
     * 接收容器交付的列表，反序列化每条 JSON，要求非空请求/事件 ID、正用户/活动 ID、正代次和版本、明确模式与有效起始桶。
     * <p>
     * 本层检查不等于完整业务身份验证，具体模式、布局和账本身份稍后在订单事务核对。
     * 畸形或单条消息体超过 batchBytes 时 NACK(false,false)，不调用订单服务。
     * <p>
     * 有效项按 batchSize 及总消息体字节数拆成有限列表，再交给 process。
     * 单条大小检查发生在 JSON 解析之后；此上限约束业务分组，并不阻止容器先收取和解析大消息。
     *
     * @param deliveries
     *                       容器本次交付的 AMQP 消息
     * @param channel
     *                       原交付所属通道，其 deliveryTag 不能跨通道使用
     * @throws IOException
     *                         向原通道发送 ACK/NACK 失败
     * @see #process
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
                        || message.requestId().isBlank() || message.messageId().isBlank()
                        || message.epoch() < 1 || message.layoutVersion() == null || message.layoutVersion() < 1
                        || message.startBucketNo() == null || message.startBucketNo() < 0
                        || !("SINGLE".equals(message.stockMode()) || "BUCKET".equals(message.stockMode()))
                        || "SINGLE".equals(message.stockMode()) && message.startBucketNo() != 0)
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
     * 按配置选择单条处理或有界批事务处理。
     * <p>
     * 开启批处理且有多条消息时调用 createPurchaseOrdersInBatch，代理成功返回后逐项记录结果并确认交付。
     * 批事务失败时先整体回滚，再逐项调用 processSingleDelivery 隔离各自结果。
     * <p>
     * 提交后的故障或 ACK 异常保留数据库结果，重复交付通过已有订单复用。
     * 批事务的在途数按一次调用统计，各结果使用同一批次开始时刻记录耗时。
     *
     * @param batch
     *                    已限制数量和消息体总字节数的有效消息列表
     * @param channel
     *                    原交付通道
     * @throws IOException
     *                         逐项确认交付失败
     * @see com.voucherx.order.service.PurchaseOrderCreationService#createPurchaseOrdersInBatch
     */
    private void process(List<Item> batch, Channel channel) throws IOException {
        if (config.batchEnabled() && batch.size() > 1) {
            List<PurchaseOrderCreationResult> results;
            long started = metrics.begin();
            try {
                results = orders.createPurchaseOrdersInBatch(batch.stream().map(Item::message).toList());
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
                    cacheResultAndAcknowledge(batch.get(i), results.get(i), channel);
                }
                return;
            }
            // 代理已回滚整个批次，尚无 ACK；有界降级到逐条，以隔离毒消息。
        }
        for (Item item : batch)
            processSingleDelivery(item, channel);
    }

    /**
     * 调用单条订单事务，并按执行结果接续确认、关闭或重试。
     * <ul>
     * <li>正常返回：记录创建或重复结果，同步成功快照并 ACK。</li>
     * <li>StaleDeliveryException：确认当前受执行条件限制的交付。</li>
     * <li>OrderBusinessException：事务回滚后调用 ledger.recordBusinessRejection，核验通过则写失败快照并
     * ACK，否则 NACK。</li>
     * <li>其他异常：事务回滚后调用 ledger.scheduleEventRetry 安排原事件，再 NACK 原交付。</li>
     * </ul>
     * <p>
     * recordBusinessRejection、scheduleEventRetry
     * 成功返回后才继续其后的确认语句；这些调用抛异常时由监听容器处理当前执行失败。
     * 订单提交后的故障点和确认异常保留已提交结果，后续重复交付使用幂等查询。
     *
     * @param item
     *                    本次 AMQP 交付及业务负载
     * @param channel
     *                    原交付通道
     * @throws IOException
     *                         发送确认失败
     * @see com.voucherx.fulfillment.RequestLedger#recordBusinessRejection
     * @see com.voucherx.fulfillment.RequestLedger#scheduleEventRetry
     */
    private void processSingleDelivery(Item item, Channel channel) throws IOException {
        long started = metrics.begin();
        PurchaseOrderCreationResult result;
        try {
            result = orders.createPurchaseOrder(item.message());
        } catch (StaleDeliveryException ex) {
            channel.basicAck(tag(item.delivery()), false);
            return;
        } catch (OrderBusinessException ex) {
            metrics.recordBusinessFailure(started);
            // 身份冲突不能关闭真实请求，也不能覆盖其状态缓存。
            if (ledger.recordBusinessRejection(item.message(), ex.failure().name())) {
                statuses.markFailed(item.message().requestId(), item.message().messageId(), ex.failure().name());
                channel.basicAck(tag(item.delivery()), false);
            } else
                channel.basicNack(tag(item.delivery()), false, false);
            return;
        } catch (Exception ex) {
            metrics.recordFailure(started);
            log.warn("下单失败，按持久化事件安排重试，requestId={}", item.message().requestId(), ex);
            ledger.scheduleEventRetry(item.message(), ex.getClass().getSimpleName());
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
        cacheResultAndAcknowledge(item, result, channel);
    }

    /**
     * 将已提交订单同步为 Redis SUCCESS，再对当前 deliveryTag 执行单条 ACK。
     * <p>
     * 快照层记录常见 Redis 访问异常；ACK 失败时订单仍已提交，重复交付继续复用该订单。
     *
     * @param item
     *                    对应业务已经完成的交付
     * @param result
     *                    新建或复用的已提交购买订单结果
     * @param channel
     *                    该 deliveryTag 所属通道
     * @throws IOException
     *                         通道 ACK 失败
     */
    private void cacheResultAndAcknowledge(Item item, PurchaseOrderCreationResult result, Channel channel)
            throws IOException {
        statuses.markSuccess(item.message().requestId(), item.message().messageId(), result.orderId());
        channel.basicAck(tag(item.delivery()), false);
    }

    /**
     * 从投递属性取当前通道内的确认标签；它与业务 requestId、messageId 是不同的标识。
     *
     * @param m
     *              AMQP 投递信封
     * @return 通道内的 deliveryTag
     */
    private long tag(Message m) {
        return m.getMessageProperties().getDeliveryTag();
    }
}
