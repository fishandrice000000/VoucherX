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

@Component
public class SeckillOrderConsumer {
    private static final Logger log=LoggerFactory.getLogger(SeckillOrderConsumer.class);
    private final ObjectMapper mapper; private final OrderPersistenceService orders;
    private final RequestLedger ledger; private final SeckillRequestStatusStore statuses;
    private final CompletionMetrics metrics; private final DeliveryProperties config;
    public SeckillOrderConsumer(ObjectMapper mapper,OrderPersistenceService orders,RequestLedger ledger,
            SeckillRequestStatusStore statuses,CompletionMetrics metrics,DeliveryProperties config) {
        this.mapper=mapper;this.orders=orders;this.ledger=ledger;this.statuses=statuses;this.metrics=metrics;this.config=config;
    }
    private record Item(Message delivery,SeckillOrderMessage message) {}
    @RabbitListener(queues=RabbitMqConfig.MAIN_QUEUE,containerFactory="deliveryContainerFactory")
    public void onMessages(List<Message> deliveries,Channel channel) throws IOException {
        List<Item> batch=new ArrayList<>();int bytes=0;
        for(Message delivery:deliveries) {
            SeckillOrderMessage message;
            try {
                message=mapper.readValue(delivery.getBody(),SeckillOrderMessage.class);
                if(message==null || message.requestId()==null || message.messageId()==null || message.voucherId()==null
                        || message.userId()==null || message.userId()<1 || message.voucherId()<1 || message.epoch()<1)
                    throw new IllegalArgumentException("消息字段不完整");
            } catch(Exception ex) { channel.basicNack(tag(delivery),false,false);continue; }
            if(delivery.getBody().length>config.batchBytes()) { channel.basicNack(tag(delivery),false,false);continue; }
            if(!batch.isEmpty() && (batch.size()>=config.batchSize() || bytes+delivery.getBody().length>config.batchBytes())) {
                process(batch,channel);batch.clear();bytes=0;
            }
            batch.add(new Item(delivery,message));bytes+=delivery.getBody().length;
        }
        if(!batch.isEmpty()) process(batch,channel);
    }
    public void onMessage(Message message,Channel channel) throws IOException { onMessages(List.of(message),channel); }
    private void process(List<Item> batch,Channel channel) throws IOException {
        if(config.batchEnabled() && batch.size()>1) {
            List<OrderPersistResult> results;
            long started=metrics.begin();
            try { results=orders.persistBatch(batch.stream().map(Item::message).toList()); }
            catch(Exception ex) { metrics.recordFailure(started);results=null; }
            finally { metrics.end(); }
            if(results!=null) {
                for(int i=0;i<batch.size();i++) {
                    if(results.get(i).created()) metrics.recordCreated(started);else metrics.recordDuplicate(started);
                    success(batch.get(i),results.get(i),channel);
                }
                return;
            }
            // 代理已回滚整个批次，尚无 ACK；有界降级到逐条，以隔离毒消息。
        }
        for(Item item:batch) one(item,channel);
    }
    private void one(Item item,Channel channel) throws IOException {
        long started=metrics.begin();
        OrderPersistResult result;
        try { result=orders.persist(item.message()); }
        catch(StaleDeliveryException ex) { channel.basicAck(tag(item.delivery()),false);return; }
        catch(OrderBusinessException ex) {
            metrics.recordBusinessFailure(started);
            // 身份冲突不能关闭真实请求，也不能覆盖其状态缓存。
            if(ledger.fail(item.message(),ex.failure().name())) {
                statuses.markFailed(item.message().requestId(),item.message().messageId(),ex.failure().name());
                channel.basicAck(tag(item.delivery()),false);
            } else channel.basicNack(tag(item.delivery()),false,false);
            return;
        } catch(Exception ex) {
            metrics.recordFailure(started);
            log.warn("下单失败，按持久化事件安排重试，requestId={}",item.message().requestId(),ex);
            ledger.defer(item.message(),ex.getClass().getSimpleName());
            channel.basicNack(tag(item.delivery()),false,false);
            return;
        } finally { metrics.end(); }
        if(result.created()) metrics.recordCreated(started);else metrics.recordDuplicate(started);
        success(item,result,channel);
    }
    private void success(Item item,OrderPersistResult result,Channel channel) throws IOException {
        statuses.markSuccess(item.message().requestId(),item.message().messageId(),result.orderId());
        channel.basicAck(tag(item.delivery()),false);
    }
    private long tag(Message m) { return m.getMessageProperties().getDeliveryTag(); }
}
