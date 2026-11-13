package com.voucherx.fulfillment;

import com.voucherx.mq.publisher.SeckillMessagePublisher;
import com.voucherx.mq.publisher.PublishResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.voucherx.fulfillment.mapper.OutboxMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.UUID;

@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxMapper outbox;
    private final RequestLedger ledger;
    private final ActivityGate gate;
    private final com.voucherx.recovery.FaultInjector faults;
    private final SeckillMessagePublisher publisher;
    private final DeliveryProperties config;
    private final Clock clock;

    public OutboxDispatcher(OutboxMapper outbox, RequestLedger ledger, ActivityGate gate,
            SeckillMessagePublisher publisher, DeliveryProperties config, Clock clock,
            com.voucherx.recovery.FaultInjector faults) {
        this.faults = faults;
        this.outbox = outbox;
        this.ledger = ledger;
        this.gate = gate;
        this.publisher = publisher;
        this.config = config;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${voucherx.delivery.interval-millis:250}")
    public void tick() {
        if (!config.enabled())
            return;
        try {
            dispatch();
        } catch (Exception ex) {
            log.warn("Outbox 扫描失败，保留记录待重试", ex);
        }
    }

    public int dispatch() {
        long now = clock.millis();
        var ids = outbox.dueEvents(now, config.scanSize());
        int sent = 0;
        for (String id : ids) {
            String token = UUID.randomUUID().toString();
            int claimed = outbox.claim(id, token, now + config.leaseMillis(), now);
            if (claimed == 0)
                continue;
            var row = outbox.findClaim(id);
            RequestRecord r = ledger.find(row.requestId());
            int attempt = row.attempts();
            if (r == null || r.terminal()) {
                finish(id, token, "DONE", "BUSINESS_TERMINAL", now);
                continue;
            }
            if (!gate.normal(r.voucherId(), r.epoch())) {
                finish(id, token, "PENDING", "ACTIVITY_FROZEN", now + config.retryMillis());
                continue;
            }
            faults.hit(com.voucherx.recovery.FaultPoint.OUTBOX_BEFORE_SEND, r.requestId(), id);
            PublishResult result;
            try {
                result = publisher.publish(r.message());
            } catch (Exception ex) {
                result = PublishResult.UNKNOWN;
                log.warn("事件发布异常，eventId={}", id, ex);
            }
            faults.hit(com.voucherx.recovery.FaultPoint.OUTBOX_AFTER_CONFIRM, r.requestId(), id);
            if (result == PublishResult.CONFIRMED) {
                sent += finish(id, token, "SENT", result.name(), now);
            } else
                finish(id, token, attempt >= config.maxAttempts() ? "QUARANTINED" : "PENDING", result.name(),
                        now + config.backoff(attempt));
        }
        return sent;
    }

    private int finish(String id, String token, String state, String result, long next) {
        return outbox.finish(id, token, state, result, next);
    }
}
