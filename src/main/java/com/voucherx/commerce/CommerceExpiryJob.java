package com.voucherx.commerce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
@Component
public class CommerceExpiryJob {
    private static final Logger log=LoggerFactory.getLogger(CommerceExpiryJob.class);
    private final JdbcTemplate db;private final CommerceService service;private final CommerceProperties config;private final Clock clock;
    public CommerceExpiryJob(JdbcTemplate db,CommerceService service,CommerceProperties config,Clock clock) {this.db=db;this.service=service;this.config=config;this.clock=clock;}
    @Scheduled(fixedDelayString="${voucherx.commerce.expiry-interval-millis:10000}")
    public void tick() {
        try {
            for(var kind:CommerceService.Kind.values()) {
                var ids=db.queryForList("SELECT id FROM "+kind.table+" WHERE payment_status='PENDING_PAYMENT' AND payment_deadline_ms<=? ORDER BY payment_deadline_ms LIMIT ?",Long.class,clock.millis(),config.scanSize());
                for(long id:ids) try {service.expire(kind,id);}catch(Exception ex) {log.warn("超时关单待重试，kind={},id={}",kind,id,ex);}
            }
            db.update("UPDATE tb_user_coupon SET state='EXPIRED',version=version+1 WHERE state='AVAILABLE' AND valid_until_ms<=? LIMIT ?",clock.millis(),config.scanSize());
        }catch(Exception ex) {log.warn("过期扫描失败",ex);}
    }
}
