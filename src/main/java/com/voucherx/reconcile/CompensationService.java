package com.voucherx.reconcile;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.fulfillment.ActivityGate;
import com.voucherx.fulfillment.RequestLedger;
import com.voucherx.seckill.service.SeckillStockService;
import com.voucherx.reconcile.mapper.CompensationMapper;
import com.voucherx.fulfillment.mapper.SeckillRequestMapper;
import com.voucherx.fulfillment.mapper.OutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

/**
 * 对尚未成功建单的请求返还发行预留：closeRequestAndPrepareCompensation
 * 提交关闭屏障，releaseReservationAndRecordResult 释放原 Redis 预留并补记结果。
 * <p>
 * 关闭与建单均按活动共享锁、请求行锁顺序竞争，建单守卫和后续扣减处于同一事务。
 * 事务传播使用 REQUIRED；closeRequestAndPrepareCompensation 所属最外层事务提交后，调用者才继续
 * releaseReservationAndRecordResult。
 * 正常 repair 和 CLI 依次调用两次服务，各自完成提交。
 *
 * @see com.voucherx.fulfillment.RequestLedger#lockAndValidateForOrderCreation
 * @see ReconciliationService#repair
 * @see com.voucherx.seckill.service.SeckillStockService#release(Long, Long,
 *      String, String, long)
 */
@Service
public class CompensationService {
    private final com.voucherx.recovery.FaultInjector faults;
    private final CompensationMapper compensations;
    private final SeckillRequestMapper requests;
    private final OutboxMapper outbox;
    private final RequestLedger ledger;
    private final ActivityGate gate;
    private final SeckillStockService stock;
    private final RepairAudit audit;
    private final Clock clock;
    private final ReconcileProperties config;

    public CompensationService(CompensationMapper compensations, SeckillRequestMapper requests, OutboxMapper outbox,
            RequestLedger ledger, ActivityGate gate, SeckillStockService stock, RepairAudit audit, Clock clock,
            ReconcileProperties config, com.voucherx.recovery.FaultInjector faults) {
        this.faults = faults;
        this.compensations = compensations;
        this.requests = requests;
        this.outbox = outbox;
        this.ledger = ledger;
        this.gate = gate;
        this.stock = stock;
        this.audit = audit;
        this.clock = clock;
        this.config = config;
    }

    /**
     * 持有 NORMAL 活动共享锁和请求行锁，为可释放预留建立数据库关闭屏障。
     * <p>
     * 要求当前代次匹配、阶段为 ACCEPTED 或 CLOSED、reservation 为 RESERVED，且对应订单不存在。
     * 将请求写 CLOSED，以请求主键插入唯一补偿意图，完成 Outbox 并记录 CLOSE_REQUEST 审计，共同提交。
     * <p>
     * 重复准备保留已存在的 compensationId、原因和进度；Redis 预留及 RESERVED 标记由
     * releaseReservationAndRecordResult 处理。
     * COMPENSATION_PENDING 是本次准备动作的标签，补偿实际状态由补偿记录给出。
     * 故障点注册在 afterCommit，触发时关闭与意图已提交。
     *
     * @param requestId
     *                      待关闭并返还发行预留的请求 ID
     * @param reason
     *                      本次关闭原因；新意图保存它，已有意图不会因此改写原原因
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             请求不存在、活动非正常、代次/阶段/预留不符或已有订单
     * @see #releaseReservationAndRecordResult(String)
     */
    @Transactional
    public void closeRequestAndPrepareCompensation(String requestId, String reason) {
        var before = ledger.find(requestId);
        if (before == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        long epoch = gate.lockNormalActivityAndGetEpoch(before.voucherId());
        var request = ledger.getRequestForUpdate(requestId);
        if (request.epoch() != epoch || "SUCCEEDED".equals(request.phase()) || !"RESERVED".equals(request.reservation())
                || (!"ACCEPTED".equals(request.phase()) && !"CLOSED".equals(request.phase())))
            throw new BusinessException(ErrorCode.CONFLICT, "当前事实不足以安全补偿");
        if (compensations.countOrders(requestId) != 0)
            throw new BusinessException(ErrorCode.CONFLICT, "订单已经提交，禁止返还发行额度");
        long now = clock.millis();
        requests.close(requestId, reason, now);
        compensations.insertIfAbsent(requestId, UUID.randomUUID().toString(), now, reason);
        outbox.markDoneByRequestId(requestId, "REQUEST_CLOSED");
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        faults.hit(com.voucherx.recovery.FaultPoint.COMPENSATION_INTENT_COMMITTED, requestId,
                                request.eventId());
                    }
                });
        audit.record(requestId, request.voucherId(), "CLOSE_REQUEST",
                "phase=" + request.phase() + ",reservation=" + request.reservation() + ",epoch=" + epoch,
                "COMPENSATION_PENDING");
    }

    /**
     * 按活动、请求、补偿记录顺序持锁，执行到期的 PENDING 补偿。
     * <p>
     * 活动须 NORMAL；补偿记录须存在。非 PENDING 返回其当前状态，尚未到期返回 PENDING。
     * 实际释放前再次核对代次、CLOSED/RESERVED 和订单不存在，再在这些锁保护下调用 Redis release。
     * <p>
     * Lua 确认释放或已释放后，将补偿与预留均记为 RELEASED，写 RELEASE_RESERVATION 审计并共同提交。
     * 未确认完成时累计正常提交的尝试次数并安排固定等待，达到预算则记 ISOLATED。
     * <p>
     * Redis 已释放而数据库补记失败时，数据库事务回滚；重试继续使用原 requestId、用户和 epoch，Lua 读取 RELEASED
     * 返回已完成，再补记数据库。
     *
     * @param requestId
     *                      已有持久化关闭事实与补偿意图的请求 ID
     * @return RELEASED、PENDING 或 ISOLATED；PENDING 也可能表示尚未到执行时刻
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             活动/记录不可用或当前关闭屏障不满足
     * @see CompensationRecord
     * @see com.voucherx.seckill.service.SeckillStockService#release(Long, Long,
     *      String, String, long)
     */
    @Transactional
    public String releaseReservationAndRecordResult(String requestId) {
        var before = ledger.find(requestId);
        if (before == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        long epoch = gate.lockNormalActivityAndGetEpoch(before.voucherId());
        var request = ledger.getRequestForUpdate(requestId);
        var compensation = compensations.selectByIdForUpdate(requestId);
        if (compensation == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "补偿意图不存在");
        String state = compensation.state();
        if (!"PENDING".equals(state))
            return state;
        if (compensation.nextAtMs() > clock.millis())
            return "PENDING";
        if (request.epoch() != epoch || !"CLOSED".equals(request.phase()) || !"RESERVED".equals(request.reservation())
                || compensations.countOrders(requestId) != 0)
            throw new BusinessException(ErrorCode.CONFLICT, "补偿屏障/归属失效");
        // 持有活动共享锁与请求锁进行有超时的 Redis 调用，冻结/重建不能越过此步骤。
        boolean released = stock.release(request.voucherId(), request.userId(), requestId, compensation.reason(),
                epoch);
        if (released)
            faults.hit(com.voucherx.recovery.FaultPoint.COMPENSATION_AFTER_RELEASE, requestId, request.eventId());
        int attempts = compensation.attempts() + 1;
        String outcome = released ? "RELEASED" : attempts >= config.maxAttempts() ? "ISOLATED" : "PENDING";
        compensations.recordResult(requestId, outcome, attempts, clock.millis() + config.retryMillis(), clock.millis());
        if (released)
            requests.markReleased(requestId, clock.millis());
        audit.record(requestId, request.voucherId(), "RELEASE_RESERVATION",
                "compensationId=" + compensation.compensationId() + ",attempt=" + attempts + ",epoch=" + epoch,
                outcome);
        return outcome;
    }
}
