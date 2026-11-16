package com.voucherx.reconcile;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.fulfillment.*;
import com.voucherx.order.mapper.PurchaseOrderEntityMapper;
import com.voucherx.seckill.service.SeckillRequestStatusCache;
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
     * action 是建议，request/orderId/outboxState
     * 可能在下一次读取时变化；真正修改须经各动作的保护条件。
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
    private final PurchaseOrderEntityMapper orders;
    private final StringRedisTemplate redis;
    private final SeckillRequestStatusCache statuses;
    private final CompensationService compensation;
    private final RepairAudit audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DeliveryProperties delivery;

    public ReconciliationService(OutboxMapper outbox, RequestLedger ledger, ActivityGate gate,
            PurchaseOrderEntityMapper orders,
            StringRedisTemplate redis, SeckillRequestStatusCache statuses, CompensationService compensation,
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
     * @param requestId
     *                      要检查的真实 requestId
     * @return 包含原始快照、归属观察和建议的诊断结果
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             请求账本不存在
     * @see ReconcileDecision#decide
     * @see com.voucherx.seckill.service.SeckillStockService#release(Long, Long,
     *      String, String, long)
     */
    public Diagnosis diagnose(String requestId) {
        var request = ledger.find(requestId);
        if (request == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        var order = orders.selectByRequestId(requestId);
        boolean match = order != null && order.getUserId() == request.userId()
                && order.getVoucherId() == request.voucherId();
        Map<Object, Object> reservation = redis.opsForHash().entries(RedisKeys.seckillRequest(requestId));
        boolean owned = requestId
                .equals(redis.opsForHash().get(RedisKeys.seckillOwners(request.voucherId()),
                        String.valueOf(request.userId())))
                && String.valueOf(request.epoch()).equals(reservation.get("epoch"))
                && String.valueOf(request.userId()).equals(reservation.get("userId"))
                && String.valueOf(request.voucherId()).equals(reservation.get("voucherId"));
        String state = outbox.stateByRequest(requestId);
        return new Diagnosis(request, ReconcileDecision.decide(request, order != null, match, owned, state), state,
                owned,
                order == null ? null : order.getId());
    }

    /**
     * 诊断请求并检查活动 NORMAL 和代次匹配，再执行建议动作。
     * <ul>
     * <li>SYNC_RESULT：尝试同步成功快照，返回 SYNC_ATTEMPTED。</li>
     * <li>ACCEPT_RESERVATION：提交账本受理；REPLAY_EVENT：通过持锁事务安排同一事件。</li>
     * <li>RELEASE_RESERVATION：依次提交 closeRequestAndPrepareCompensation 和
     * releaseReservationAndRecordResult。</li>
     * <li>WAIT 或 ISOLATE：返回等待或隔离查证结论。</li>
     * </ul>
     * <p>
     * 门禁不符时返回 ACTIVITY_ISOLATED。分支结束后保存动作审计，补偿事务还会保存阶段审计。
     * repair 按普通服务调用执行，各分支使用自身事务；顶层审计通常在已提交动作之后单独写入。
     *
     * @param requestId
     *                      请求 ID
     * @return 本次动作或保持结论；不能仅按返回标签推断库存实际变化次数
     * @see CompensationService#closeRequestAndPrepareCompensation
     * @see CompensationService#releaseReservationAndRecordResult
     */
    public String repair(String requestId) {
        Diagnosis diagnosis = diagnose(requestId);
        var request = diagnosis.request();
        if (!gate.normal(request.voucherId(), request.epoch()))
            return "ACTIVITY_ISOLATED";
        String result = switch (diagnosis.action()) {
            case SYNC_RESULT -> {
                statuses.markSuccess(requestId, request.eventId(), diagnosis.orderId());
                yield "SYNC_ATTEMPTED";
            }
            case ACCEPT_RESERVATION -> {
                ledger.acceptAndEnqueueEvent(requestId);
                yield "ACCEPTED";
            }
            case RELEASE_RESERVATION -> {
                compensation.closeRequestAndPrepareCompensation(requestId,
                        request.reason() == null ? "BUSINESS_FAILED" : request.reason());
                yield compensation.releaseReservationAndRecordResult(requestId);
            }
            case REPLAY_EVENT -> scheduleEventRedelivery(request);
            case WAIT -> "WAIT";
            case ISOLATE -> "ISOLATED";
        };
        audit.record(requestId, request.voucherId(), diagnosis.action().name(),
                "phase=" + request.phase() + ",reservation=" + request.reservation() + ",outbox="
                        + diagnosis.outboxState(),
                result);
        return result;
    }

    /**
     * 在 TransactionTemplate 事务中取得 NORMAL 活动共享锁及请求行锁，重新确认请求仍 ACCEPTED 且代次一致。
     * <p>
     * 随后仅条件修改原 SENT 事件，不直接发送 MQ，也不生成新业务身份。
     * 条件更新命中后在同一事务内读取事件状态，PENDING 返回 REDELIVERY_SCHEDULED，QUARANTINED 返回
     * EVENT_QUARANTINED。
     * 状态已变或事件更新未命中返回 STATE_CHANGED。事务模板默认 REQUIRED，已有外层事务时会加入。
     *
     * @param before
     *                   诊断时的请求快照，仅用于定位活动和请求
     * @return 已安排重投、事件已隔离或状态已变化的动作结果
     * @see com.voucherx.fulfillment.mapper.OutboxMapper#scheduleReplay
     */
    private String scheduleEventRedelivery(RequestRecord before) {
        return tx.execute(status -> {
            long epoch = gate.lockNormalActivityAndGetEpoch(before.voucherId());
            var request = ledger.getRequestForUpdate(before.requestId());
            if (epoch != request.epoch() || !"ACCEPTED".equals(request.phase()))
                return "STATE_CHANGED";
            int changed = outbox.scheduleReplay(request.requestId(), delivery.maxAttempts(), clock.millis());
            if (changed != 1)
                return "STATE_CHANGED";
            String eventState = outbox.stateByRequest(request.requestId());
            if ("PENDING".equals(eventState))
                return "REDELIVERY_SCHEDULED";
            if ("QUARANTINED".equals(eventState))
                return "EVENT_QUARANTINED";
            return "STATE_CHANGED";
        });
    }
}
