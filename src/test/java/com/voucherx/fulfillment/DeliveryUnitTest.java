package com.voucherx.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.voucherx.admission.CompletionMetrics;
import com.voucherx.mq.consumer.SeckillOrderConsumer;
import com.voucherx.order.service.*;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.seckill.service.SeckillRequestStatusCache;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 通过 Mockito 验证消费确认顺序，模拟订单服务、账本、缓存及 RabbitMQ 通道。
 * <p>
 * inOrder 检查事务服务返回、状态更新与 ACK 的调用顺序，并验证批处理失败后的一次逐条回退。
 * 真实事务回滚与组件交付验证见 DeliveryComponentTest 及库存集成测试。
 *
 * @see com.voucherx.mq.consumer.SeckillOrderConsumer
 * @see DeliveryComponentTest
 */
class DeliveryUnitTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PurchaseOrderCreationService orders = mock(PurchaseOrderCreationService.class);
    private final RequestLedger ledger = mock(RequestLedger.class);
    private final SeckillRequestStatusCache statuses = mock(SeckillRequestStatusCache.class);
    private final Channel channel = mock(Channel.class);
    private final SeckillOrderMessage first = message("first", 1L);

    /**
     * 构造默认投递参数，只按入参切换批事务开关；不启动监听容器或定时扫描。
     *
     * @param batch
     *                  是否启用批事务尝试
     * @return 单元测试用投递配置
     */
    static DeliveryProperties config(boolean batch) {
        return new DeliveryProperties(true, 32, 30000, 8, 1000, batch, 16, 1048576, 50);
    }

    /**
     * 构造 SINGLE、版本及代次 1 的固定业务消息，以 requestId 为基础生成事件 ID。
     *
     * @param id
     *                 用例请求 ID
     * @param user
     *                 用例用户 ID
     * @return 供模拟持久化调用匹配的消息
     */
    static SeckillOrderMessage message(String id, long user) {
        return new SeckillOrderMessage("e-" + id, id, user, 1L, 100, "SINGLE", 0, 1, 1);
    }

    /**
     * 装配使用模拟业务依赖和真实 JSON/计数器的消费者，隔离实际数据库、网络及故障注入。
     *
     * @param batch
     *                  本用例是否尝试批事务
     * @return 可直接调用 onMessages 的消费者
     */
    private SeckillOrderConsumer consumer(boolean batch) {
        return new SeckillOrderConsumer(mapper, orders, ledger, statuses, new CompletionMetrics(), config(batch),
                mock(com.voucherx.recovery.FaultInjector.class));
    }

    /**
     * 把业务对象序列化成 AMQP 消息并设置 deliveryTag，让用例观察确认的是哪次交付。
     *
     * @param value
     *                  测试业务负载
     * @param tag
     *                  模拟通道内的交付标签
     * @return 具有 JSON 消息体的模拟 AMQP 交付
     * @throws Exception
     *                       JSON 编码失败
     */
    private Message delivery(SeckillOrderMessage value, long tag) throws Exception {
        var props = new MessageProperties();
        props.setDeliveryTag(tag);
        return new Message(mapper.writeValueAsBytes(value), props);
    }

    /**
     * 模拟持久化返回订单 7，验证先调用 createPurchaseOrder，再写 SUCCESS，最后
     * basicAck(1,false)；只证明调用顺序，不执行真实事务。
     *
     * @throws Exception
     *                       测试序列化或通道交互异常
     */
    @Test
    void acknowledgesOnlyAfterPersistenceReturns() throws Exception {
        when(orders.createPurchaseOrder(first)).thenReturn(new PurchaseOrderCreationResult(7L, true));
        consumer(false).onMessages(java.util.List.of(delivery(first, 1)), channel);
        var sequence = inOrder(orders, statuses, channel);
        sequence.verify(orders).createPurchaseOrder(first);
        sequence.verify(statuses).markSuccess("first", "e-first", 7L);
        sequence.verify(channel).basicAck(1, false);
    }

    /**
     * 模拟事务服务抛临时异常，验证先调用账本 scheduleEventRetry，再 NACK(1,false,false)，且未
     * ACK；持久重试是否真实提交需组件验证。
     *
     * @throws Exception
     *                       测试序列化或通道交互异常
     */
    @Test
    void rollbackRequeuesDurablyBeforeDeadLetteringOriginal() throws Exception {
        when(orders.createPurchaseOrder(first)).thenThrow(new IllegalStateException("DB unavailable"));
        consumer(false).onMessages(java.util.List.of(delivery(first, 1)), channel);
        var sequence = inOrder(ledger, channel);
        sequence.verify(ledger).scheduleEventRetry(first, "IllegalStateException");
        sequence.verify(channel).basicNack(1, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    /**
     * 模拟失效交付异常，验证消费者 ACK 本次交付，并保持账本和状态缓存原值。
     *
     * @throws Exception
     *                       测试序列化或通道交互异常
     */
    @Test
    void staleDeliveryDoesNotCloseOriginalRequest() throws Exception {
        when(orders.createPurchaseOrder(first)).thenThrow(new StaleDeliveryException());
        consumer(false).onMessages(java.util.List.of(delivery(first, 1)), channel);
        verifyNoInteractions(ledger, statuses);
        verify(channel).basicAck(1, false);
    }

    /**
     * 模拟身份冲突且账本二次校验不允许关闭，验证不写状态缓存，NACK 原交付。
     *
     * @throws Exception
     *                       测试序列化或通道交互异常
     */
    @Test
    void identityConflictCannotOverwriteBusinessResult() throws Exception {
        when(orders.createPurchaseOrder(first))
                .thenThrow(new OrderBusinessException(OrderFailure.INVALID_REQUEST, "conflict"));
        when(ledger.recordBusinessRejection(first, "INVALID_REQUEST")).thenReturn(false);
        consumer(false).onMessages(java.util.List.of(delivery(first, 1)), channel);
        verifyNoInteractions(statuses);
        verify(channel).basicNack(1, false, false);
    }

    /**
     * 两项的批服务调用抛异常后，验证按
     * first.createPurchaseOrder→ACK1→second.createPurchaseOrder→ACK2
     * 有界回退；模拟异常本身不是数据库回滚证据。
     *
     * @throws Exception
     *                       测试序列化或通道交互异常
     */
    @Test
    void batchRollbackSplitsWithoutEarlyAcknowledgement() throws Exception {
        var second = message("second", 2L);
        when(orders.createPurchaseOrdersInBatch(List.of(first, second)))
                .thenThrow(new IllegalStateException("bad batch"));
        when(orders.createPurchaseOrder(first)).thenReturn(new PurchaseOrderCreationResult(7L, true));
        when(orders.createPurchaseOrder(second)).thenReturn(new PurchaseOrderCreationResult(8L, true));
        consumer(true).onMessages(List.of(delivery(first, 1), delivery(second, 2)), channel);
        var sequence = inOrder(orders, channel);
        sequence.verify(orders).createPurchaseOrdersInBatch(List.of(first, second));
        sequence.verify(orders).createPurchaseOrder(first);
        sequence.verify(channel).basicAck(1, false);
        sequence.verify(orders).createPurchaseOrder(second);
        sequence.verify(channel).basicAck(2, false);
    }

    /**
     * 投递非法 JSON，验证在入口就 NACK，订单服务、账本及状态缓存都未被调用。
     *
     * @throws Exception
     *                       测试序列化或通道交互异常
     */
    @Test
    void malformedMessageNeverCallsBusinessServices() throws Exception {
        consumer(false).onMessages(java.util.List.of(new Message("bad-json".getBytes(), new MessageProperties())),
                channel);
        verifyNoInteractions(orders, ledger, statuses);
        verify(channel).basicNack(0, false, false);
    }

    /**
     * 验证 RequestRecord.matches 不只比较 requestId：相同请求但换用户或换代次都不能视为同一个有效业务消息。
     */
    @Test
    void identityIncludesEpochAndUser() {
        var r = new RequestRecord("first", "e-first", 1, 1, "ACCEPTED", "RESERVED", 1, "SINGLE", 0, 1, null, null, 100,
                100);
        assertThat(r.matches(first)).isTrue();
        assertThat(r.matches(message("first", 2L))).isFalse();
        assertThat(r.matches(new SeckillOrderMessage("e-first", "first", 1L, 1L, 100, "SINGLE", 0, 1, 2))).isFalse();
    }

    /**
     * 验证默认配置的大尝试次数退避为 60000 毫秒，且构造器拒绝 batchSize=10000 的过大批次；不证明网络发送次数或事务耗时上限。
     */
    @Test
    void retryBackoffIsBoundedAndConfigurationRejectsUnboundedBatch() {
        assertThat(config(false).backoff(100)).isEqualTo(60000);
        assertThatThrownBy(() -> new DeliveryProperties(true, 1, 30000, 8, 1000, true, 10000, 1024, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 缺少任一布局或代次字段的 JSON 在监听入口被隔离，不调用建单服务或账本修复。
     *
     * @throws Exception
     *                       JSON 编码或模拟通道交互失败
     */
    @Test
    void incompleteMessageCannotReachBusinessProcessing() throws Exception {
        var listener = consumer(false);
        long tag = 10;
        for (String field : java.util.List.of("epoch", "stockMode", "startBucketNo", "layoutVersion")) {
            var json = mapper.valueToTree(first);
            ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove(field);
            var props = new MessageProperties();
            props.setDeliveryTag(tag++);
            listener.onMessages(java.util.List.of(new Message(mapper.writeValueAsBytes(json), props)), channel);
        }
        verifyNoInteractions(orders, ledger, statuses);
        verify(channel, times(4)).basicNack(anyLong(), eq(false), eq(false));
    }

}
