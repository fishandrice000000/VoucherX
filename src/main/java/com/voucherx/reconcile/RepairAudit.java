package com.voucherx.reconcile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
@Component
public class RepairAudit {
    private final JdbcTemplate db;private final Clock clock;
    public RepairAudit(JdbcTemplate db,Clock clock) {this.db=db;this.clock=clock;}
    public void record(String request,long voucher,String action,String evidence,String outcome) {
        db.update("INSERT INTO tb_repair_audit(request_id,voucher_id,action,evidence,outcome,created_at_ms) VALUES (?,?,?,?,?,?)",
                request,voucher,action,evidence.substring(0,Math.min(1024,evidence.length())),outcome,clock.millis());
    }
}
