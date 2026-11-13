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
 * 03 的消费执行者：校验 AMQP JSON，再经事务服务创建购买订单，最后处理该通道本次交付。
 * <p>
 * 没有 HTTP 的 ThreadLocal 身份，用户和活动来自消息并由账本守卫核对。
 * 默认逐条事务，可选有界批事务；事务提交后、ACK 前中断会导致重复交付，固定请求身份使其复用原订单。
 * <p>
 * 暂时性失败先安排 Outbox 重试，再 NACK 原消息且不原队列重排；业务拒绝和旧投递有各自确认分支。
 * 消息确认不等于 Redis 预留已释放，也不是购买付款或发券。
 *
 * @see com.voucherx.order.service.OrderPersistenceService
 * @see com.voucherx.fulfillment.RequestLedger
 * @see com.voucherx.fulfillment.DeliveryListenerConfig
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
     * 接收容器交付的列表，反序列化每条 JSON，要求请求/事件 ID 非 null、用户/活动 ID 为正且 epoch 至少为 1。
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
     * @param message
     *                    AMQP 投递
     * @param channel
     *                    对应的 RabbitMQ 通道
     * @throws IOException
     *                         消息确认失败
     */
    public void onMessage(Message message, Channel channel) throws IOException {
        onMessages(List.of(message), channel);
    }

    /**
     * 默认直接逐条 one；开启批处理且有多条时，先调用 persistBatch 共用一个 MySQL 事务。
     * <p>
     * 代理正常返回后才逐项记录新建/重复结果并调用 success；批次中途抛异常则先整体回滚，此时尚未 ACK，
     * 随后只做一轮逐条降级，不无限递归拆分。逐条失败会按 one 的分类处理。
     * <p>
     * 提交后的故障点或 ACK 异常不会进入“批事务回滚后拆分”分支，已经提交的结果保留，未来重投复用订单。
     * 批事务执行在途按一段计，结果耗时沿用批次起点，不是每单独立事务时间。
     *
     * @param batch
     *                    已限制数量和消息体总字节数的有效消息列表
     * @param channel
     *                    原交付通道
     * @throws IOException
     *                         逐项确认交付失败
     * @see com.voucherx.order.service.OrderPersistenceService#persistBatch
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
     * 调用订单事务服务并按返回或异常分类；正常返回后才记录成功/重复、更新缓存及单条 ACK。
     * <ul>
     * <li>StaleDeliveryException：只 ACK 此投递，不关闭原请求或改缓存。</li>
     * <li>OrderBusinessException：回滚后调用 ledger.fail 再核验；允许确认时写失败缓存并 ACK，否则
     * NACK。</li>
     * <li>其他异常：回滚后调用 ledger.defer 安排原事件重试，再 NACK(false,false) 原投递。</li>
     * </ul>
     * <p>
     * fail/defer 自己抛异常时，执行不会继续到后面的确认语句；不能把未提交的重试意图当作已持久化。
     * 正常事务已提交后执行的故障点、状态记录或 ACK 出错，也不回滚该购买单。
     *
     * @param item
     *                    本次 AMQP 交付及业务负载
     * @param channel
     *                    原交付通道
     * @throws IOException
     *                         发送确认失败
     * @see com.voucherx.fulfillment.RequestLedger#fail
     * @see com.voucherx.fulfillment.RequestLedger#defer
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
     * 在数据库结果已提交后，尽力将 Redis 状态写 SUCCESS，再 basicAck(tag,false) 仅确认本次交付。
     * <p>
     * 状态存储层捕获常见 Redis 数据访问异常，缓存失败不撤销数据库结果。
     * ACK 写出失败时数据库仍成功，未来重复交付由已有订单幂等处理；不使用 multiple=true 跨越其他未完成交付。
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
    private void success(Item item, OrderPersistResult result, Channel channel) throws IOException {
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
