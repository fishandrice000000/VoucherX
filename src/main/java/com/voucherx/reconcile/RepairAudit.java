package com.voucherx.reconcile;

import com.voucherx.reconcile.mapper.RepairAuditMapper;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public class RepairAudit {
    private final RepairAuditMapper mapper;
    private final Clock clock;

    public RepairAudit(RepairAuditMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public void record(String request, long voucher, String action, String evidence, String outcome) {
        mapper.insert(request, voucher, action, evidence.substring(0, Math.min(1024, evidence.length())), outcome,
                clock.millis());
    }
}
