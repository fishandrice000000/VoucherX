package com.voucherx.reconcile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.voucherx.fulfillment.mapper.SeckillRequestMapper;
import com.voucherx.reconcile.mapper.CompensationMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public class ReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private final SeckillRequestMapper requests;
    private final CompensationMapper compensations;
    private final ReconciliationService service;
    private final CompensationService compensation;
    private final ReconcileProperties config;
    private final Clock clock;

    public ReconciliationJob(SeckillRequestMapper requests, CompensationMapper compensations,
            ReconciliationService service, CompensationService compensation, ReconcileProperties config, Clock clock) {
        this.requests = requests;
        this.compensations = compensations;
        this.service = service;
        this.compensation = compensation;
        this.config = config;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${voucherx.reconcile.interval-millis:5000}")
    public void tick() {
        if (!config.enabled())
            return;
        try {
            scan();
        } catch (Exception ex) {
            log.warn("对账扫描失败，保留持久化进度", ex);
        }
    }

    public int scan() {
        long now = clock.millis();
        var ids = requests.dueForCheck(now, now - config.staleMillis(), config.scanSize());
        int repaired = 0;
        for (String id : ids) {
            // 条件领取下次检查窗口；具体业务修复仍由数据库事务/幂等协议保护。
            if (requests.claimCheck(id, now + config.retryMillis(), now) == 0)
                continue;
            try {
                service.repair(id);
                repaired++;
            } catch (Exception ex) {
                log.warn("请求对账待重试，requestId={}", id, ex);
            }
        }
        var pending = compensations.due(now, config.scanSize());
        for (String id : pending)
            try {
                compensation.execute(id);
            } catch (Exception ex) {
                log.warn("补偿待重试，requestId={}", id, ex);
            }
        return repaired;
    }
}
