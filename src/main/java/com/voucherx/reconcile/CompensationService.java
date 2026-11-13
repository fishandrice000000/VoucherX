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
 * 05 的发行预留补偿：prepare 先建立数据库关闭屏障，execute 再释放原 Redis 预留并补记结果。
 * <p>
 * 这里只处理尚未成功建单的请求，不处理 04 的消费锁释放、已付款退款或成功订单的发行额度返还。
 * 关闭与建单按同一活动共享锁 → 请求行锁顺序竞争，消费者必须在 guard 之后仍持锁扣库存并插单。
 * <p>
 * 方法使用默认 REQUIRED，不强制 REQUIRES_NEW。按本协议调用时必须先让 prepare 所属事务提交，
 * 再调用 execute；正常 repair/CLI 没有包住两步的外层事务，两次服务调用才分别提交。
 * 不能仅凭 Java 方法已经返回，就假定一个尚未结束的外层事务已建立关闭屏障。
 *
 * @see com.voucherx.fulfillment.RequestLedger#guard
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
     * 持有 NORMAL 活动共享锁和请求行锁，要求当前代次匹配、phase 为 ACCEPTED/CLOSED、reservation 为
     * RESERVED，且没有订单。
     * <p>
     * 再将请求写 CLOSED，用 INSERT IGNORE 建补偿意图，把 Outbox 标 DONE 并清掉认领令牌，记录 CLOSE_REQUEST
     * 审计，一起提交。
     * Redis 此时仍少一份，reservation 仍 RESERVED；关闭只是阻止原请求未来成功，尚未代表已返还。
     * <p>
     * 请求 ID 是补偿表主键，重复准备不覆盖已有 compensationId、原因或进度，也不会重置已隔离的补偿。
     * 审计中的 COMPENSATION_PENDING 是本次准备动作标签，不保证刚插入了新记录或已有记录已恢复 PENDING。
     * 已释放的请求不再满足 RESERVED 前提，不能把 close 当成任意状态可重复的重置接口。
     * <p>
     * COMPENSATION_INTENT_COMMITTED 故障点注册在 afterCommit，那里抛异常时关闭和意图已提交，不会倒退成未关闭。
     * 有外层事务时注解加入它，必须等最外层提交后才可继续释放。
     *
     * @param id
     *                   待关闭并返还发行预留的请求 ID
     * @param reason
     *                   本次关闭原因；新意图保存它，已有意图不会因此改写原原因
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             请求不存在、活动非正常、代次/阶段/预留不符或已有订单
     * @see #execute(String)
     */
    @Transactional
    public void prepare(String id, String reason) {
        var before = ledger.find(id);
        if (before == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        long epoch = gate.requireNormal(before.voucherId());
        var r = ledger.lock(id);
        if (r.epoch() != epoch || "SUCCEEDED".equals(r.phase()) || !"RESERVED".equals(r.reservation())
                || (!"ACCEPTED".equals(r.phase()) && !"CLOSED".equals(r.phase())))
            throw new BusinessException(ErrorCode.CONFLICT, "当前事实不足以安全补偿");
        if (compensations.countOrders(id) != 0)
            throw new BusinessException(ErrorCode.CONFLICT, "订单已经提交，禁止返还发行额度");
        long now = clock.millis();
        requests.close(id, reason, now);
        compensations.insertIfAbsent(id, UUID.randomUUID().toString(), now, reason);
        outbox.completeRequest(id, "REQUEST_CLOSED");
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        faults.hit(com.voucherx.recovery.FaultPoint.COMPENSATION_INTENT_COMMITTED, id, r.eventId());
                    }
                });
        audit.record(id, r.voucherId(), "CLOSE_REQUEST",
                "phase=" + r.phase() + ",reservation=" + r.reservation() + ",epoch=" + epoch, "COMPENSATION_PENDING");
    }

    /**
     * 先要求活动 NORMAL，按活动 → 请求 → 补偿记录加锁；无补偿记录拒绝执行。
     * <p>
     * 补偿非 PENDING 时返回原状态；尚未到 nextAtMs 返回 PENDING。准备实际调用 Redis
     * 时再要求请求同代次、CLOSED/RESERVED 且没有订单。
     * 因此完成后的重放仍先依赖活动可用及记录存在，不是任何活动状态下都无条件返回 RELEASED。
     * <p>
     * 持有这些数据库锁调用 release，使冻结/重建的活动独占锁无法越过正在执行的释放。
     * Lua 返回本次释放或原已释放均视为成功；补偿改 RELEASED、请求 reservation 改 RELEASED，并写
     * RELEASE_RESERVATION 审计，一起提交。
     * 失败布尔结果按已记录 attempts+1 安排固定等待，达到配置预算则补偿 ISOLATED；不猜成“库存缺失可直接加一”。
     * <p>
     * Redis 不属于 MySQL 事务。若 Lua 已加回一份，随后故障或数据库提交失败，原补偿仍可能 PENDING；
     * 重试用原 requestId/用户/epoch，Lua 根据 RELEASED 返回 2，不再 INCR，再补记数据库。
     * 抛异常回滚时本次 attempts 增加也不保留，预算统计的是正常提交的执行结果，不是所有网络调用次数。
     *
     * @param id
     *               已有持久化关闭事实与补偿意图的请求 ID
     * @return RELEASED、PENDING 或 ISOLATED；PENDING 也可能表示尚未到执行时刻
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             活动/记录不可用或当前关闭屏障不满足
     * @see CompensationRecord
     * @see com.voucherx.seckill.service.SeckillStockService#release(Long, Long,
     *      String, String, long)
     */
    @Transactional
    public String execute(String id) {
        var before = ledger.find(id);
        if (before == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        long epoch = gate.requireNormal(before.voucherId());
        var r = ledger.lock(id);
        var compensation = compensations.lock(id);
        if (compensation == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "补偿意图不存在");
        String state = compensation.state();
        if (!"PENDING".equals(state))
            return state;
        if (compensation.nextAtMs() > clock.millis())
            return "PENDING";
        if (r.epoch() != epoch || !"CLOSED".equals(r.phase()) || !"RESERVED".equals(r.reservation())
                || compensations.countOrders(id) != 0)
            throw new BusinessException(ErrorCode.CONFLICT, "补偿屏障/归属失效");
        // 持有活动共享锁与请求锁进行有超时的 Redis 调用，冻结/重建不能越过此步骤。
        boolean released = stock.release(r.voucherId(), r.userId(), id, compensation.reason(), epoch);
        if (released)
            faults.hit(com.voucherx.recovery.FaultPoint.COMPENSATION_AFTER_RELEASE, id, r.eventId());
        int attempts = compensation.attempts() + 1;
        String outcome = released ? "RELEASED" : attempts >= config.maxAttempts() ? "ISOLATED" : "PENDING";
        compensations.recordResult(id, outcome, attempts, clock.millis() + config.retryMillis(), clock.millis());
        if (released)
            requests.markReleased(id, clock.millis());
        audit.record(id, r.voucherId(), "RELEASE_RESERVATION",
                "compensationId=" + compensation.compensationId() + ",attempt=" + attempts + ",epoch=" + epoch,
                outcome);
        return outcome;
    }
}
