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

@Service
public class ReconciliationService {
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
