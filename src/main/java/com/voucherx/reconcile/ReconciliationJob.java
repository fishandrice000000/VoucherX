package com.voucherx.reconcile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.voucherx.fulfillment.mapper.SeckillRequestMapper;
import com.voucherx.reconcile.mapper.CompensationMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

/**
 * 有界对账扫描入口，自动调度默认关闭。
 * <p>
 * 先检查到期且进展达到观察阈值的请求，再执行到期 PENDING 补偿。
 * 多个执行者可选中相同候选，具体修改通过请求、补偿行锁和 Redis 释放标记保持幂等。
 *
 * @see ReconcileProperties
 * @see ReconciliationService
 * @see CompensationService
 */
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

    /**
     * 按固定延迟调 scan；enabled=false 时不自动扫描，异常记日志，已保存的下次检查时间和补偿进度留待后续。
     */
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

    /**
     * 取本轮起始 now，有限查询 next_check 到期且 updated_at 不晚于 now-staleMillis 的请求，阶段不限。
     * <p>
     * 逐条用条件更新推迟 next_check，成功后调用 repair；一次正常返回即累加计数，即使结果是 WAIT 或 ISOLATED。
     * 单条 repair 异常记日志后继续；领取本身或扫描查询异常可结束本轮。
     * <p>
     * 随后有限查询到期 PENDING 补偿并逐条 execute，这部分调用不计入返回数。
     * 两轮查询都使用本轮起始 now，执行期间新到期对象可留到下轮；显式 scan 不检查 enabled，便于维护或测试调用。
     *
     * @return 本轮请求 repair 正常返回的次数；不含后半补偿执行数，也不等于返还库存数
     * @see com.voucherx.fulfillment.mapper.SeckillRequestMapper#claimCheck
     * @see com.voucherx.reconcile.mapper.CompensationMapper#due
     */
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
