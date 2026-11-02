package com.voucherx.fulfillment;

import com.voucherx.mq.publisher.SeckillMessagePublisher;
import com.voucherx.mq.publisher.PublishResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.UUID;

@Component
public class OutboxDispatcher {
    private static final Logger log=LoggerFactory.getLogger(OutboxDispatcher.class);
    private final JdbcTemplate db; private final RequestLedger ledger; private final ActivityGate gate;
    private final SeckillMessagePublisher publisher; private final DeliveryProperties config; private final Clock clock;
    public OutboxDispatcher(JdbcTemplate db,RequestLedger ledger,ActivityGate gate,SeckillMessagePublisher publisher,DeliveryProperties config,Clock clock) {
        this.db=db;this.ledger=ledger;this.gate=gate;this.publisher=publisher;this.config=config;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${voucherx.delivery.interval-millis:250}")
    public void tick() {
        if(!config.enabled()) return;
        try { dispatch(); } catch(Exception ex) { log.warn("Outbox 扫描失败，保留记录待重试",ex); }
    }
    public int dispatch() {
        long now=clock.millis();
        var ids=db.queryForList("""
                SELECT event_id FROM tb_outbox WHERE (state='PENDING' AND next_at_ms<=?)
                   OR (state='CLAIMED' AND lease_until_ms<=?) ORDER BY next_at_ms LIMIT ?
                """,String.class,now,now,config.scanSize());
        int sent=0;
        for(String id:ids) {
            String token=UUID.randomUUID().toString();
            int claimed=db.update("""
                    UPDATE tb_outbox SET state='CLAIMED',claim_token=?,lease_until_ms=?,attempts=attempts+1
                     WHERE event_id=? AND ((state='PENDING' AND next_at_ms<=?) OR (state='CLAIMED' AND lease_until_ms<=?))
                    """,token,now+config.leaseMillis(),id,now,now);
            if(claimed==0) continue;
            var row=db.queryForMap("SELECT request_id,attempts FROM tb_outbox WHERE event_id=?",id);
            RequestRecord r=ledger.find((String)row.get("request_id"));
            int attempt=((Number)row.get("attempts")).intValue();
            if(r==null || r.terminal()) { finish(id,token,"DONE","BUSINESS_TERMINAL",now);continue; }
            if(!gate.normal(r.voucherId(),r.epoch())) { finish(id,token,"PENDING","ACTIVITY_FROZEN",now+config.retryMillis());continue; }
            PublishResult result;
            try { result=publisher.publish(r.message()); }
            catch(Exception ex) { result=PublishResult.UNKNOWN; log.warn("事件发布异常，eventId={}",id,ex); }
            if(result==PublishResult.CONFIRMED) { sent+=finish(id,token,"SENT",result.name(),now); }
            else finish(id,token,attempt>=config.maxAttempts()?"QUARANTINED":"PENDING",result.name(),now+config.backoff(attempt));
        }
        return sent;
    }
    private int finish(String id,String token,String state,String result,long next) {
        return db.update("UPDATE tb_outbox SET state=?,last_result=?,next_at_ms=?,claim_token=NULL WHERE event_id=? AND claim_token=? AND state='CLAIMED'",state,result,next,id,token);
    }
}
