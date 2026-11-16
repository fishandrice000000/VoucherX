package com.voucherx.reconcile;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.fulfillment.*;
import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.seckill.service.SeckillRequestStatusStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.voucherx.fulfillment.mapper.OutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.Map;

/**
 * 请求级对账编排：diagnose 收集事实，repair 按建议接续受理、重投、结果同步或补偿。
 * <p>
 * 诊断分别读取 MySQL 和 Redis，各修改入口在自己的事务中重新核对状态、身份及代次。
 * WAIT 和 ISOLATED 等返回值表示本次处理结论，执行进度由请求、事件、补偿和审计记录共同描述。
 *
 * @see ReconcileDecision
 * @see CompensationService
 * @see ReconciliationJob
 */
@Service
public class ReconciliationService {
    /**
     * 一次诊断时的多源观察结果；不持有锁，不构成执行修复的独立授权。
     * <p>
     * action 是建议，request/orderId/outboxState 可能在下一次读取时变化；真正修改须经各动作的保护条件。
     *
     * @param request
     *                               原请求账本快照
     * @param action
     *                               纯分类函数给出的建议
     * @param outboxState
     *                               此时查到的 Outbox 状态，可为空
     * @param reservationMatches
     *                               owners 与请求 Hash 的指定身份字段是否匹配
     * @param orderId
     *                               查询到的订单 ID，可为空；有 ID 不表示订单归属一定匹配
     */
    public record Diagnosis(RequestRecord request, ReconcileDecision action, String outboxState,
            boolean reservationMatches, Long orderId) {
    }

    private final OutboxMapper outbox;
    private final RequestLedger ledger;
    private final ActivityGate gate;
    private final VoucherOrderMapper orders;
    private final StringRedisTemplate redis;
    private final SeckillRequestStatusStore statuses;
    private final CompensationService compensation;
    private final RepairAudit audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DeliveryProperties delivery;

    public ReconciliationService(OutboxMapper outbox, RequestLedger ledger, ActivityGate gate,
            VoucherOrderMapper orders,
            StringRedisTemplate redis, SeckillRequestStatusStore statuses, CompensationService compensation,
            RepairAudit audit,
            org.springframework.transaction.PlatformTransactionManager manager, Clock clock,
            DeliveryProperties delivery) {
        this.outbox = outbox;
        this.ledger = ledger;
        this.gate = gate;
        this.orders = orders;
        this.redis = redis;
        this.statuses = statuses;
        this.compensation = compensation;
        this.audit = audit;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
        this.delivery = delivery;
    }

    /**
     * 按请求 ID 读账本和订单，再读 Redis 请求 Hash、用户预留 owners，最后读 Outbox 状态形成 Diagnosis。
     * <p>
     * 订单匹配检查用户与活动；Redis 归属检查 owners[userId]=requestId，以及 Hash 的
     * epoch/userId/voucherId。
     * 这里没有比较 Hash 的 requestId、status 或完整布局，也不检查活动门禁；不要把 reservationMatches
     * 当作完整释放条件。
     * <p>
     * 方法不写修复审计或业务状态。Redis 读取失败会抛出，不把无法读取伪装为“确无预留”。
     *
     * @param id
     *               要检查的真实 requestId
     * @return 包含原始快照、归属观察和建议的诊断结果
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             请求账本不存在
     * @see ReconcileDecision#decide
     * @see com.voucherx.seckill.service.SeckillStockService#release(Long, Long,
     *      String, String, long)
     */
    public Diagnosis diagnose(String id) {
        var r = ledger.find(id);
        if (r == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        var order = orders.selectByRequestId(id);
        boolean match = order != null && order.getUserId() == r.userId() && order.getVoucherId() == r.voucherId();
        Map<Object, Object> reservation = redis.opsForHash().entries(RedisKeys.seckillRequest(id));
        boolean owned = id
                .equals(redis.opsForHash().get(RedisKeys.seckillOwners(r.voucherId()), String.valueOf(r.userId())))
                && String.valueOf(r.epoch()).equals(reservation.get("epoch"))
                && String.valueOf(r.userId()).equals(reservation.get("userId"))
                && String.valueOf(r.voucherId()).equals(reservation.get("voucherId"));
        String state = outbox.stateByRequest(id);
        return new Diagnosis(r, ReconcileDecision.decide(r, order != null, match, owned, state), state, owned,
                order == null ? null : order.getId());
    }

    /**
     * 诊断请求并检查活动 NORMAL 和代次匹配，再执行建议动作。
     * <ul>
     * <li>SYNC_RESULT：尽力同步成功快照，返回 SYNCED。</li>
     * <li>ACCEPT_RESERVATION：提交账本受理；REPLAY_EVENT：通过持锁事务安排同一事件。</li>
     * <li>RELEASE_RESERVATION：依次提交 prepare 和 execute。</li>
     * <li>WAIT 或 ISOLATE：返回等待或隔离查证结论。</li>
     * </ul>
     * <p>
     * 门禁不符时返回 ACTIVITY_ISOLATED。分支结束后保存动作审计，补偿事务还会保存阶段审计。
     * repair 按普通服务调用执行，各分支使用自身事务；顶层审计通常在已提交动作之后单独写入。
     *
     * @param id
     *               请求 ID
     * @return 本次动作或保持结论；不能仅按返回标签推断库存实际变化次数
     * @see CompensationService#prepare
     * @see CompensationService#execute
     */
    public String repair(String id) {
        Diagnosis d = diagnose(id);
        var r = d.request();
        if (!gate.normal(r.voucherId(), r.epoch()))
            return "ACTIVITY_ISOLATED";
        String result = switch (d.action()) {
            case SYNC_RESULT -> {
                statuses.markSuccess(id, r.eventId(), d.orderId());
                yield "SYNCED";
            }
            case ACCEPT_RESERVATION -> {
                ledger.accept(id);
                yield "ACCEPTED";
            }
            case RELEASE_RESERVATION -> {
                compensation.prepare(id, r.reason() == null ? "BUSINESS_FAILED" : r.reason());
                yield compensation.execute(id);
            }
            case REPLAY_EVENT -> replay(r);
            case WAIT -> "WAIT";
            case ISOLATE -> "ISOLATED";
        };
        audit.record(id, r.voucherId(), d.action().name(),
                "phase=" + r.phase() + ",reservation=" + r.reservation() + ",outbox=" + d.outboxState(), result);
        return result;
    }

    /**
     * 在 TransactionTemplate 事务中取得 NORMAL 活动共享锁及请求行锁，重新确认请求仍 ACCEPTED 且代次一致。
     * <p>
     * 随后仅条件修改原 SENT 事件，不直接发送 MQ，也不生成新业务身份。
     * 更新命中返回 REPLAY_SCHEDULED，但 SQL 会按投递预算选择 PENDING 或 QUARANTINED；该标签不保证一定可继续发送。
     * 状态已变或事件更新未命中返回 STATE_CHANGED。事务模板默认 REQUIRED，已有外层事务时会加入。
     *
     * @param before
     *                   诊断时的请求快照，仅用于定位活动和请求
     * @return 事件更新命中标签或状态已变标签
     * @see com.voucherx.fulfillment.mapper.OutboxMapper#scheduleReplay
     */
    private String replay(RequestRecord before) {
        return tx.execute(status -> {
            long epoch = gate.requireNormal(before.voucherId());
            var r = ledger.lock(before.requestId());
            if (epoch != r.epoch() || !"ACCEPTED".equals(r.phase()))
                return "STATE_CHANGED";
            int changed = outbox.scheduleReplay(r.requestId(), delivery.maxAttempts(), clock.millis());
            return changed == 1 ? "REPLAY_SCHEDULED" : "STATE_CHANGED";
        });
    }
}
