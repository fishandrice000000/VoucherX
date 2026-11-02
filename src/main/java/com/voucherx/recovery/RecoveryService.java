package com.voucherx.recovery;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.fulfillment.RequestRecord;
import com.voucherx.reconcile.RepairAudit;
import com.voucherx.voucher.mapper.SeckillVoucherBucketMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;

@Service
public class RecoveryService {
    public record Control(long voucherId,String state,long epoch,String recoveryId) {}
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;
    private final RepairAudit audit;
    private final Clock clock;
    private final RecoveryProperties config;
    private final SeckillVoucherBucketMapper buckets;
    private final DefaultRedisScript<Long> publish;
    private final FaultInjector faults;

    public RecoveryService(JdbcTemplate db,StringRedisTemplate redis,PlatformTransactionManager manager,
            RepairAudit audit,Clock clock,RecoveryProperties config,SeckillVoucherBucketMapper buckets,
            @Qualifier("recoveryPublishScript") DefaultRedisScript<Long> publish,FaultInjector faults) {
        this.db=db;this.redis=redis;this.tx=new TransactionTemplate(manager);this.audit=audit;this.clock=clock;
        this.config=config;this.buckets=buckets;this.publish=publish;this.faults=faults;
    }
    public Control status(long voucher) {
        var rows=db.query("SELECT * FROM tb_activity_control WHERE voucher_id=?",(r,n)->new Control(voucher,r.getString("state"),r.getLong("epoch"),r.getString("recovery_id")),voucher);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"活动控制记录不存在");
        return rows.getFirst();
    }
    public Control freeze(long voucher,String reason) {
        Control frozen=tx.execute(status->{
            if(db.queryForObject("SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id=?",Long.class,voucher)!=1)
                throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
            db.update("INSERT IGNORE INTO tb_activity_control(voucher_id) VALUES (?)",voucher);
            Control c=lock(voucher);
            if(!"NORMAL".equals(c.state())) return c;
            String id=UUID.randomUUID().toString();long epoch=Math.addExact(c.epoch(),1);long now=clock.millis();
            db.update("UPDATE tb_activity_control SET state='FROZEN',epoch=?,recovery_id=?,reason=? WHERE voucher_id=?",epoch,id,reason,voucher);
            db.update("INSERT INTO tb_recovery_run(recovery_id,voucher_id,epoch,state,created_at_ms,updated_at_ms) VALUES (?,?,?,'FROZEN',?,?)",id,voucher,epoch,now,now);
            audit.record(null,voucher,"FREEZE","oldEpoch="+c.epoch()+",reason="+reason,"epoch="+epoch);
            return new Control(voucher,"FROZEN",epoch,id);
        });
        // DB 屏障先成立；旧 Redis 预扣随后被新代次 gate 拒绝。期间所有意图仍可扫描。
        frozen=tx.execute(status->{
            Control current=lock(voucher);
            if(!"NORMAL".equals(current.state())) ensureFrozen(current);
            return current;
        });
        faults.hit(FaultPoint.RECOVERY_FROZEN,String.valueOf(voucher),frozen.recoveryId());
        return frozen;
    }
    public Control recover(long voucher) {
        Control c=tx.execute(status->{
            Control current=lock(voucher);
            if(!"NORMAL".equals(current.state())) ensureFrozen(current);
            return current;
        });
        if("NORMAL".equals(c.state())) return c;
        if("FROZEN".equals(c.state())) {
            tx.executeWithoutResult(status->plan(lock(voucher)));
            faults.hit(FaultPoint.RECOVERY_PLANNED,String.valueOf(voucher),c.recoveryId());
        }
        if("RECONCILING".equals(status(voucher).state())) tx.executeWithoutResult(status->rebuild(lock(voucher)));
        if("REBUILDING".equals(status(voucher).state())) tx.executeWithoutResult(status->resume(lock(voucher)));
        return status(voucher);
    }
    private Control lock(long voucher) {
        var rows=db.query("SELECT * FROM tb_activity_control WHERE voucher_id=? FOR UPDATE",
                (r,n)->new Control(voucher,r.getString("state"),r.getLong("epoch"),r.getString("recovery_id")),voucher);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);return rows.getFirst();
    }
    private void ensureFrozen(Control c) {
        redis.opsForHash().putAll(RedisKeys.seckillGate(c.voucherId()),Map.of("state","FROZEN","epoch",String.valueOf(c.epoch())));
    }
    private void plan(Control c) {
        if(!"FROZEN".equals(c.state())) return;
        ensureFrozen(c);
        requests(c.voucherId(),r->{
            long orderCount=db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE request_id=? AND voucher_id=? AND user_id=?",Long.class,r.requestId(),c.voucherId(),r.userId());
            if("SUCCEEDED".equals(r.phase()) && orderCount!=1 || !"SUCCEEDED".equals(r.phase()) && orderCount!=0)
                throw new IllegalStateException("订单与请求终态冲突: "+r.requestId());
            String phase=r.phase(),reservation=r.reservation(),reason=r.reason();
            if("INTENT".equals(phase)) {
                var evidence=redis.opsForHash().entries(RedisKeys.seckillRequest(r.requestId()));
                boolean owned=r.requestId().equals(redis.opsForHash().get(RedisKeys.seckillOwners(c.voucherId()),String.valueOf(r.userId())))
                        && String.valueOf(r.epoch()).equals(evidence.get("epoch"))
                        && String.valueOf(r.userId()).equals(evidence.get("userId"))
                        && String.valueOf(r.voucherId()).equals(evidence.get("voucherId"));
                // 未持久化受理的意图，无肯定证据时关闭；新代次已封住旧预扣，不会迟到污染。
                phase=owned?"ACCEPTED":"CLOSED";reservation=owned?"RESERVED":"NONE";
                reason=owned?"RECOVERY_ACCEPTED":"RECOVERY_NOT_ACCEPTED";
            }
            if("ACCEPTED".equals(phase) && !"RESERVED".equals(reservation)) throw new IllegalStateException("受理请求缺少预留事实");
            db.update("UPDATE tb_seckill_request SET phase=?,reservation=?,reason=?,epoch=?,updated_at_ms=? WHERE request_id=?",
                    phase,reservation,reason,c.epoch(),clock.millis(),r.requestId());
            if("ACCEPTED".equals(phase)) {
                db.update("""
                        INSERT INTO tb_outbox(event_id,request_id,next_at_ms) VALUES (?,?,?)
                        ON DUPLICATE KEY UPDATE state='PENDING',attempts=0,next_at_ms=VALUES(next_at_ms),claim_token=NULL,lease_until_ms=0,last_result='RECOVERY_REBOUND'
                        """,r.eventId(),r.requestId(),clock.millis());
            } else db.update("UPDATE tb_outbox SET state='DONE',claim_token=NULL,last_result='RECOVERY_TERMINAL' WHERE request_id=?",r.requestId());
        });
        Long duplicate=db.queryForObject("""
                SELECT COUNT(*) FROM (SELECT user_id FROM tb_seckill_request WHERE voucher_id=? AND phase='ACCEPTED' GROUP BY user_id HAVING COUNT(*)>1) d
                """,Long.class,c.voucherId());
        Long conflict=db.queryForObject("""
                SELECT COUNT(*) FROM tb_seckill_request r JOIN tb_voucher_order o ON r.voucher_id=o.voucher_id AND r.user_id=o.user_id
                 WHERE r.voucher_id=? AND r.phase='ACCEPTED'
                """,Long.class,c.voucherId());
        if(duplicate!=0 || conflict!=0) throw new IllegalStateException("用户唯一预留事实冲突");
        RecoveryPlan plan=currentPlan(c.voucherId());
        db.update("UPDATE tb_recovery_run SET available=?,reserved=?,confirmed=?,state='RECONCILING',updated_at_ms=? WHERE recovery_id=?",
                plan.available(),plan.reserved(),plan.confirmed(),clock.millis(),c.recoveryId());
        transition(c,"RECONCILING");
        audit.record(null,c.voucherId(),"PLAN_RECOVERY",plan.toString(),"RECONCILING");
    }
    private RecoveryPlan currentPlan(long voucher) {
        var activity=db.queryForMap("SELECT * FROM tb_seckill_voucher WHERE voucher_id=?",voucher);
        long remaining;
        if("BUCKET".equals(activity.get("stock_mode"))) {
            int layout=((Number)activity.get("layout_version")).intValue();int count=((Number)activity.get("bucket_count")).intValue();
            var stats=buckets.selectLayoutStats(voucher,layout);
            if(count<1 || stats==null || !stats.covers(count) || buckets.sumTotal(voucher,layout)!=number(activity,"total_stock"))
                throw new IllegalStateException("MySQL 库存桶布局不完整");
            remaining=buckets.sumRemaining(voucher,layout);
        } else if("SINGLE".equals(activity.get("stock_mode"))) remaining=number(activity,"stock");
        else throw new IllegalStateException("未知库存模式");
        long confirmed=db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",Long.class,voucher);
        long reserved=db.queryForObject("SELECT COUNT(*) FROM tb_seckill_request WHERE voucher_id=? AND phase='ACCEPTED'",Long.class,voucher);
        return RecoveryPlan.calculate(number(activity,"total_stock"),remaining,confirmed,reserved);
    }
    private void rebuild(Control c) {
        if(!"RECONCILING".equals(c.state())) return;
        ensureFrozen(c);RecoveryPlan plan=currentPlan(c.voucherId());
        if(!plan.equals(savedPlan(c.recoveryId()))) throw new IllegalStateException("恢复快照已经变化");
        String prefix="voucherx:recovery:"+c.recoveryId();
        String stageStock=prefix+":stock",stageUsers=prefix+":users",stageOwners=prefix+":owners";
        redis.delete(List.of(stageStock,stageUsers,stageOwners));
        redis.opsForValue().set(stageStock,String.valueOf(plan.available()));
        long after=0;int seen=0;
        while(true) {
            var page=db.queryForList("SELECT id,user_id,request_id,message_id FROM tb_voucher_order WHERE voucher_id=? AND id>? ORDER BY id LIMIT ?",c.voucherId(),after,config.pageSize());
            if(page.isEmpty()) break;
            for(var order:page) {
                if(++seen>config.maxRecords()) throw new IllegalStateException("恢复记录超出配置预算");
                String user=String.valueOf(number(order,"user_id")),id=(String)order.get("request_id");
                redis.opsForSet().add(stageUsers,user);redis.opsForHash().put(stageOwners,user,id);
                redis.opsForHash().putAll(RedisKeys.seckillRequest(id),Map.of("requestId",id,"userId",user,"voucherId",String.valueOf(c.voucherId()),
                        "epoch",String.valueOf(c.epoch()),"status","SUCCESS","orderId",String.valueOf(number(order,"id")),"messageId",order.get("message_id")));
                after=number(order,"id");
            }
        }
        requests(c.voucherId(),r->{
            if("ACCEPTED".equals(r.phase())) {
                String user=String.valueOf(r.userId());redis.opsForSet().add(stageUsers,user);redis.opsForHash().put(stageOwners,user,r.requestId());
            }
            String status=switch(r.phase()) {case "SUCCEEDED"->"SUCCESS";case "ACCEPTED"->"PROCESSING";default->"RELEASED";};
            Map<String,String> snapshot=new HashMap<>(Map.of("requestId",r.requestId(),"userId",String.valueOf(r.userId()),"voucherId",String.valueOf(r.voucherId()),
                    "epoch",String.valueOf(c.epoch()),"status",status,"messageId",r.eventId(),"stockMode",r.stockMode(),"startBucketNo",String.valueOf(r.startBucketNo()),"layoutVersion",String.valueOf(r.layoutVersion())));
            if(r.orderId()!=null) snapshot.put("orderId",String.valueOf(r.orderId()));
            redis.opsForHash().putAll(RedisKeys.seckillRequest(r.requestId()),snapshot);
            redis.persist(RedisKeys.seckillRequest(r.requestId()));
        });
        Long result=redis.execute(publish,List.of(RedisKeys.seckillStock(c.voucherId()),RedisKeys.seckillUsers(c.voucherId()),RedisKeys.seckillOwners(c.voucherId()),
                RedisKeys.seckillGate(c.voucherId()),stageStock,stageUsers,stageOwners),String.valueOf(c.epoch()),c.recoveryId(),String.valueOf(plan.available()),String.valueOf(plan.reserved()+plan.confirmed()));
        if(result==null || result!=1) throw new IllegalStateException("Redis 恢复发布校验失败: "+result);
        faults.hit(FaultPoint.RECOVERY_PUBLISHED,String.valueOf(c.voucherId()),c.recoveryId());
        // 发布后仍冻结；再次执行只会按同一权威快照覆盖，不做增量加库存。
        db.update("UPDATE tb_seckill_request SET reservation='RELEASED',updated_at_ms=? WHERE voucher_id=? AND phase='CLOSED' AND reservation='RESERVED'",clock.millis(),c.voucherId());
        db.update("UPDATE tb_compensation c JOIN tb_seckill_request r ON c.request_id=r.request_id SET c.state='RELEASED',c.updated_at_ms=? WHERE r.voucher_id=? AND r.phase='CLOSED' AND r.reservation='RELEASED'",clock.millis(),c.voucherId());
        transition(c,"REBUILDING");
        audit.record(null,c.voucherId(),"REBUILD",plan.toString(),"PUBLISHED_FROZEN");
    }
    private void resume(Control c) {
        if(!"REBUILDING".equals(c.state())) return;
        RecoveryPlan plan=savedPlan(c.recoveryId());
        if(!plan.equals(currentPlan(c.voucherId()))) throw new IllegalStateException("复流前权威库存发生变化");
        var gate=redis.opsForHash().entries(RedisKeys.seckillGate(c.voucherId()));
        if(!String.valueOf(c.epoch()).equals(gate.get("epoch")) || !c.recoveryId().equals(gate.get("publishedRecovery"))
                || !String.valueOf(plan.available()).equals(redis.opsForValue().get(RedisKeys.seckillStock(c.voucherId())))
                || !Long.valueOf(plan.reserved()+plan.confirmed()).equals(redis.opsForSet().size(RedisKeys.seckillUsers(c.voucherId())))
                || !Long.valueOf(plan.reserved()+plan.confirmed()).equals(redis.opsForHash().size(RedisKeys.seckillOwners(c.voucherId()))))
            throw new IllegalStateException("Redis 恢复内容不完整，禁止复流");
        long unmatched=db.queryForObject("""
                SELECT COUNT(*) FROM tb_seckill_request r LEFT JOIN tb_outbox o ON r.request_id=o.request_id
                 WHERE r.voucher_id=? AND r.phase='ACCEPTED' AND (r.epoch<>? OR o.event_id IS NULL OR o.state<>'PENDING')
                """,Long.class,c.voucherId(),c.epoch());
        if(unmatched!=0) throw new IllegalStateException("有效请求未安排恢复投递");
        faults.hit(FaultPoint.RECOVERY_BEFORE_RESUME,String.valueOf(c.voucherId()),c.recoveryId());
        redis.opsForHash().put(RedisKeys.seckillGate(c.voucherId()),"state","NORMAL");
        db.update("UPDATE tb_activity_control SET state='NORMAL',reason=NULL WHERE voucher_id=? AND epoch=?",c.voucherId(),c.epoch());
        db.update("UPDATE tb_recovery_run SET state='DONE',updated_at_ms=? WHERE recovery_id=?",clock.millis(),c.recoveryId());
        audit.record(null,c.voucherId(),"RESUME",plan.toString(),"NORMAL");
    }
    private RecoveryPlan savedPlan(String id) {
        var row=db.queryForMap("SELECT available,reserved,confirmed FROM tb_recovery_run WHERE recovery_id=?",id);
        return new RecoveryPlan(number(row,"available"),number(row,"reserved"),number(row,"confirmed"));
    }
    private void transition(Control c,String state) {
        db.update("UPDATE tb_activity_control SET state=? WHERE voucher_id=? AND epoch=?",state,c.voucherId(),c.epoch());
        db.update("UPDATE tb_recovery_run SET state=?,updated_at_ms=? WHERE recovery_id=?",state,clock.millis(),c.recoveryId());
    }
    private void requests(long voucher,Consumer<RequestRecord> consumer) {
        String after="";int seen=0;
        while(true) {
            var page=db.query("SELECT * FROM tb_seckill_request WHERE voucher_id=? AND request_id>? ORDER BY request_id LIMIT ?",RequestRecord::read,voucher,after,config.pageSize());
            if(page.isEmpty()) return;
            for(var r:page) {if(++seen>config.maxRecords()) throw new IllegalStateException("恢复请求超出配置预算");consumer.accept(r);after=r.requestId();}
        }
    }
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
}
