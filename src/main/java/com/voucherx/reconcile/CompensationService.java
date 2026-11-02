package com.voucherx.reconcile;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.fulfillment.ActivityGate;
import com.voucherx.fulfillment.RequestLedger;
import com.voucherx.seckill.service.SeckillStockService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class CompensationService {
    private final com.voucherx.recovery.FaultInjector faults;
    private final JdbcTemplate db;private final RequestLedger ledger;private final ActivityGate gate;
    private final SeckillStockService stock;private final RepairAudit audit;private final Clock clock;private final ReconcileProperties config;
    public CompensationService(JdbcTemplate db,RequestLedger ledger,ActivityGate gate,SeckillStockService stock,RepairAudit audit,Clock clock,ReconcileProperties config,com.voucherx.recovery.FaultInjector faults) {
        this.faults=faults;
        this.db=db;this.ledger=ledger;this.gate=gate;this.stock=stock;this.audit=audit;this.clock=clock;this.config=config;
    }
    /** 显式关闭或已确定失败：请求终态与补偿意图在同一事务中提交。 */
    @Transactional
    public void prepare(String id,String reason) {
        var before=ledger.find(id);
        if(before==null) throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        long epoch=gate.requireNormal(before.voucherId());var r=ledger.lock(id);
        if(r.epoch()!=epoch || "SUCCEEDED".equals(r.phase()) || !"RESERVED".equals(r.reservation())
                || (!"ACCEPTED".equals(r.phase()) && !"CLOSED".equals(r.phase())))
            throw new BusinessException(ErrorCode.CONFLICT,"当前事实不足以安全补偿");
        if(db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE request_id=?",Long.class,id)!=0)
            throw new BusinessException(ErrorCode.CONFLICT,"订单已经提交，禁止返还发行额度");
        long now=clock.millis();
        db.update("UPDATE tb_seckill_request SET phase='CLOSED',reason=?,updated_at_ms=? WHERE request_id=?",reason,now,id);
        db.update("INSERT IGNORE INTO tb_compensation(request_id,compensation_id,next_at_ms,reason,updated_at_ms) VALUES (?,?,?,?,?)",id,UUID.randomUUID().toString(),now,reason,now);
        db.update("UPDATE tb_outbox SET state='DONE',claim_token=NULL,last_result='REQUEST_CLOSED' WHERE request_id=?",id);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    faults.hit(com.voucherx.recovery.FaultPoint.COMPENSATION_INTENT_COMMITTED,id,r.eventId());
                }
            });
        audit.record(id,r.voucherId(),"CLOSE_REQUEST","phase="+r.phase()+",reservation="+r.reservation()+",epoch="+epoch,"COMPENSATION_PENDING");
    }
    @Transactional
    public String execute(String id) {
        var before=ledger.find(id);
        if(before==null) throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        long epoch=gate.requireNormal(before.voucherId());var r=ledger.lock(id);
        var rows=db.queryForList("SELECT * FROM tb_compensation WHERE request_id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"补偿意图不存在");
        var compensation=rows.getFirst();String state=(String)compensation.get("state");
        if(!"PENDING".equals(state)) return state;
        if(((Number)compensation.get("next_at_ms")).longValue()>clock.millis()) return "PENDING";
        if(r.epoch()!=epoch || !"CLOSED".equals(r.phase()) || !"RESERVED".equals(r.reservation())
                || db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE request_id=?",Long.class,id)!=0)
            throw new BusinessException(ErrorCode.CONFLICT,"补偿屏障/归属失效");
        // 持有活动共享锁与请求锁进行有超时的 Redis 调用，冻结/重建不能越过此步骤。
        boolean released=stock.release(r.voucherId(),r.userId(),id,(String)compensation.get("reason"),epoch);
        if(released) faults.hit(com.voucherx.recovery.FaultPoint.COMPENSATION_AFTER_RELEASE,id,r.eventId());
        int attempts=((Number)compensation.get("attempts")).intValue()+1;
        String outcome=released?"RELEASED":attempts>=config.maxAttempts()?"ISOLATED":"PENDING";
        db.update("UPDATE tb_compensation SET state=?,attempts=?,next_at_ms=?,updated_at_ms=? WHERE request_id=?",outcome,attempts,clock.millis()+config.retryMillis(),clock.millis(),id);
        if(released) db.update("UPDATE tb_seckill_request SET reservation='RELEASED',updated_at_ms=? WHERE request_id=?",clock.millis(),id);
        audit.record(id,r.voucherId(),"RELEASE_RESERVATION","compensationId="+compensation.get("compensation_id")+",attempt="+attempts+",epoch="+epoch,outcome);
        return outcome;
    }
}
