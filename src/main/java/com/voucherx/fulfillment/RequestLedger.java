package com.voucherx.fulfillment;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.order.service.OrderBusinessException;
import com.voucherx.order.service.OrderFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class RequestLedger {
    private final JdbcTemplate db;
    private final ActivityGate gate;
    private final Clock clock;
    private final DeliveryProperties config;
    public RequestLedger(JdbcTemplate db, ActivityGate gate, Clock clock,DeliveryProperties config) { this.db=db;this.gate=gate;this.clock=clock;this.config=config; }
    public RequestRecord find(String id) {
        var rows=db.query("SELECT * FROM tb_seckill_request WHERE request_id=?",RequestRecord::read,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public RequestRecord byKey(long userId,String key) {
        var rows=db.query("SELECT * FROM tb_seckill_request WHERE user_id=? AND client_key=?",RequestRecord::read,userId,key);
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Transactional
    public RequestRecord intent(long user,long voucher,String key,String mode,int bucket,int layout) {
        long epoch=gate.requireNormal(voucher);
        String id=UUID.randomUUID().toString();long now=clock.millis();
        db.update("""
                INSERT INTO tb_seckill_request(request_id,event_id,user_id,voucher_id,client_key,
                  epoch,stock_mode,start_bucket_no,layout_version,created_at_ms,updated_at_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,id,UUID.randomUUID().toString(),user,voucher,key,epoch,mode,bucket,layout,now,now);
        return find(id);
    }
    public RequestRecord lock(String id) {
        var rows=db.query("SELECT * FROM tb_seckill_request WHERE request_id=? FOR UPDATE",RequestRecord::read,id);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        return rows.getFirst();
    }
    @Transactional
    public RequestRecord accept(String id) {
        RequestRecord before=find(id);
        if(before==null) throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        long epoch=gate.requireNormal(before.voucherId());
        RequestRecord r=lock(id);
        if(r.epoch()!=epoch) throw new BusinessException(ErrorCode.CONFLICT,"请求恢复代次已失效");
        if("INTENT".equals(r.phase())) {
            db.update("UPDATE tb_seckill_request SET phase='ACCEPTED',reservation='RESERVED',updated_at_ms=? WHERE request_id=?",clock.millis(),id);
            db.update("INSERT INTO tb_outbox(event_id,request_id,next_at_ms) VALUES (?,?,?)",r.eventId(),id,clock.millis());
        }
        return find(id);
    }
    /** 调用者必须处于扣库存/建单的同一事务中。 */
    public RequestRecord guard(SeckillOrderMessage message) {
        var c=gate.lock(message.voucherId());
        if(find(message.requestId())==null) throw new OrderBusinessException(OrderFailure.INVALID_REQUEST,"无持久化受理依据");
        RequestRecord r=lock(message.requestId());
        if(!r.matches(message)) throw new OrderBusinessException(OrderFailure.INVALID_REQUEST,"消息身份/布局/代次不匹配");
        if(!"NORMAL".equals(c.state()) || c.epoch()!=message.epoch()) throw new StaleDeliveryException();
        if("CLOSED".equals(r.phase())) throw new OrderBusinessException(OrderFailure.REQUEST_CLOSED,"请求已经关闭");
        if(!"ACCEPTED".equals(r.phase()) && !"SUCCEEDED".equals(r.phase())) throw new StaleDeliveryException();
        return r;
    }
    public void succeed(String id,long orderId) {
        db.update("UPDATE tb_seckill_request SET phase='SUCCEEDED',order_id=?,updated_at_ms=? WHERE request_id=? AND phase='ACCEPTED'",orderId,clock.millis(),id);
    }
    @Transactional
    public void rejectUnreserved(String id,String reason) {
        RequestRecord before=find(id);gate.lock(before.voucherId());
        RequestRecord r=lock(id);
        if("INTENT".equals(r.phase())) db.update("UPDATE tb_seckill_request SET phase='CLOSED',reservation='NONE',reason=?,updated_at_ms=? WHERE request_id=?",reason,clock.millis(),id);
    }
    @Transactional
    public boolean fail(SeckillOrderMessage message,String reason) {
        var c=gate.lock(message.voucherId());
        if(find(message.requestId())==null) return false;
        RequestRecord r=lock(message.requestId());
        if(!r.matches(message) || !"NORMAL".equals(c.state()) || c.epoch()!=r.epoch()) return false;
        if("SUCCEEDED".equals(r.phase())) return false;
        if("ACCEPTED".equals(r.phase())) db.update("UPDATE tb_seckill_request SET phase='CLOSED',reason=?,updated_at_ms=? WHERE request_id=?",reason,clock.millis(),r.requestId());
        return true;
    }
    @Transactional
    public void defer(SeckillOrderMessage message,String reason) {
        var c=gate.lock(message.voucherId());
        RequestRecord r=lock(message.requestId());
        if(!r.matches(message) || r.terminal() || c.epoch()!=r.epoch() || !"NORMAL".equals(c.state())) return;
        db.update("UPDATE tb_outbox SET state=IF(attempts>=?,'QUARANTINED','PENDING'),next_at_ms=?,last_result=?,claim_token=NULL WHERE request_id=?",
                config.maxAttempts(),clock.millis()+config.retryMillis(),reason,r.requestId());
    }
    public void quarantine(String id,String reason) {
        db.update("UPDATE tb_seckill_request SET reason=?,updated_at_ms=? WHERE request_id=? AND phase='ACCEPTED'",reason,clock.millis(),id);
        db.update("UPDATE tb_outbox SET state='QUARANTINED',last_result=?,claim_token=NULL WHERE request_id=?",reason,id);
    }
}
