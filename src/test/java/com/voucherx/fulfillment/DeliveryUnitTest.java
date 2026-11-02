package com.voucherx.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.voucherx.admission.CompletionMetrics;
import com.voucherx.mq.consumer.SeckillOrderConsumer;
import com.voucherx.order.service.*;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.seckill.service.SeckillRequestStatusStore;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryUnitTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final OrderPersistenceService orders=mock(OrderPersistenceService.class);
    private final RequestLedger ledger=mock(RequestLedger.class);
    private final SeckillRequestStatusStore statuses=mock(SeckillRequestStatusStore.class);
    private final Channel channel=mock(Channel.class);
    private final SeckillOrderMessage first=message("first",1L);
    static DeliveryProperties config(boolean batch) { return new DeliveryProperties(true,32,30000,8,1000,batch,16,1048576,50); }
    static SeckillOrderMessage message(String id,long user) { return new SeckillOrderMessage("e-"+id,id,user,1L,100,"SINGLE",0,1,1); }
    private SeckillOrderConsumer consumer(boolean batch) { return new SeckillOrderConsumer(mapper,orders,ledger,statuses,new CompletionMetrics(),config(batch),mock(com.voucherx.recovery.FaultInjector.class)); }
    private Message delivery(SeckillOrderMessage value,long tag) throws Exception {
        var props=new MessageProperties();props.setDeliveryTag(tag);return new Message(mapper.writeValueAsBytes(value),props);
    }
    @Test void acknowledgesOnlyAfterPersistenceReturns() throws Exception {
        when(orders.persist(first)).thenReturn(new OrderPersistResult(7L,true));
        consumer(false).onMessage(delivery(first,1),channel);
        var sequence=inOrder(orders,statuses,channel);
        sequence.verify(orders).persist(first);sequence.verify(statuses).markSuccess("first","e-first",7L);sequence.verify(channel).basicAck(1,false);
    }
    @Test void rollbackRequeuesDurablyBeforeDeadLetteringOriginal() throws Exception {
        when(orders.persist(first)).thenThrow(new IllegalStateException("DB unavailable"));
        consumer(false).onMessage(delivery(first,1),channel);
        var sequence=inOrder(ledger,channel);sequence.verify(ledger).defer(first,"IllegalStateException");sequence.verify(channel).basicNack(1,false,false);
        verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void staleDeliveryDoesNotCloseOriginalRequest() throws Exception {
        when(orders.persist(first)).thenThrow(new StaleDeliveryException());
        consumer(false).onMessage(delivery(first,1),channel);
        verifyNoInteractions(ledger,statuses);verify(channel).basicAck(1,false);
    }
    @Test void identityConflictCannotOverwriteBusinessResult() throws Exception {
        when(orders.persist(first)).thenThrow(new OrderBusinessException(OrderFailure.INVALID_REQUEST,"conflict"));
        when(ledger.fail(first,"INVALID_REQUEST")).thenReturn(false);
        consumer(false).onMessage(delivery(first,1),channel);
        verifyNoInteractions(statuses);verify(channel).basicNack(1,false,false);
    }
    @Test void batchRollbackSplitsWithoutEarlyAcknowledgement() throws Exception {
        var second=message("second",2L);
        when(orders.persistBatch(List.of(first,second))).thenThrow(new IllegalStateException("bad batch"));
        when(orders.persist(first)).thenReturn(new OrderPersistResult(7L,true));when(orders.persist(second)).thenReturn(new OrderPersistResult(8L,true));
        consumer(true).onMessages(List.of(delivery(first,1),delivery(second,2)),channel);
        var sequence=inOrder(orders,channel);sequence.verify(orders).persistBatch(List.of(first,second));
        sequence.verify(orders).persist(first);sequence.verify(channel).basicAck(1,false);
        sequence.verify(orders).persist(second);sequence.verify(channel).basicAck(2,false);
    }
    @Test void malformedMessageNeverCallsBusinessServices() throws Exception {
        consumer(false).onMessage(new Message("bad-json".getBytes(),new MessageProperties()),channel);
        verifyNoInteractions(orders,ledger,statuses);verify(channel).basicNack(0,false,false);
    }
    @Test void identityIncludesEpochAndUser() {
        var r=new RequestRecord("first","e-first",1,1,"ACCEPTED","RESERVED",1,"SINGLE",0,1,null,null,100,100);
        assertThat(r.matches(first)).isTrue();assertThat(r.matches(message("first",2L))).isFalse();
        assertThat(r.matches(new SeckillOrderMessage("e-first","first",1L,1L,100,"SINGLE",0,1,2))).isFalse();
    }
    @Test void retryBackoffIsBoundedAndConfigurationRejectsUnboundedBatch() {
        assertThat(config(false).backoff(100)).isEqualTo(60000);
        assertThatThrownBy(()->new DeliveryProperties(true,1,30000,8,1000,true,10000,1024,50)).isInstanceOf(IllegalArgumentException.class);
    }
}
