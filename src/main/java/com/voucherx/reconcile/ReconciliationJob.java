package com.voucherx.reconcile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
@Component
public class ReconciliationJob {
    private static final Logger log=LoggerFactory.getLogger(ReconciliationJob.class);
    private final JdbcTemplate db;private final ReconciliationService service;private final CompensationService compensation;private final ReconcileProperties config;private final Clock clock;
    public ReconciliationJob(JdbcTemplate db,ReconciliationService service,CompensationService compensation,ReconcileProperties config,Clock clock) {
        this.db=db;this.service=service;this.compensation=compensation;this.config=config;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${voucherx.reconcile.interval-millis:5000}")
    public void tick() {
        if(!config.enabled()) return;
        try { scan(); }catch(Exception ex) { log.warn("对账扫描失败，保留持久化进度",ex); }
    }
    public int scan() {
        long now=clock.millis();
        var ids=db.queryForList("SELECT request_id FROM tb_seckill_request WHERE next_check_at_ms<=? AND updated_at_ms<=? ORDER BY next_check_at_ms,request_id LIMIT ?",
                String.class,now,now-config.staleMillis(),config.scanSize());
        int repaired=0;
        for(String id:ids) {
            // 条件领取下次检查窗口；具体业务修复仍由数据库事务/幂等协议保护。
            if(db.update("UPDATE tb_seckill_request SET next_check_at_ms=? WHERE request_id=? AND next_check_at_ms<=?",now+config.retryMillis(),id,now)==0) continue;
            try {service.repair(id);repaired++;} catch(Exception ex) {log.warn("请求对账待重试，requestId={}",id,ex);}
        }
        var pending=db.queryForList("SELECT request_id FROM tb_compensation WHERE state='PENDING' AND next_at_ms<=? ORDER BY next_at_ms LIMIT ?",String.class,now,config.scanSize());
        for(String id:pending) try {compensation.execute(id);} catch(Exception ex) {log.warn("补偿待重试，requestId={}",id,ex);}
        return repaired;
    }
}
