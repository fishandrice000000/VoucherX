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

/**
 * 把已提交的 Outbox 事件交给 RabbitMQ，接续 HTTP 线程结束后的抢券流程。
 * <p>
 * 短数据库操作用于认领和回写，等待 Broker 确认发生在这些操作之间，不用长事务包住网络等待。
 * 租约过期或确认结果未知都可能导致重复发送；消费者按固定请求身份幂等建单。
 *
 * @see com.voucherx.mq.publisher.SeckillMessagePublisher
 * @see com.voucherx.mq.consumer.SeckillOrderConsumer
 */
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

    /**
     * 按配置的固定延迟触发扫描；关闭投递开关时跳过，扫描异常保留数据库记录待下轮处理。
     */
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

    /**
     * 扫描到期且受理依据有效的事件，用随机 claim_token 认领并增加尝试次数。
     * <p>
     * 业务已终止的事件标为 DONE；活动冻结则延后。发布得到 CONFIRMED 后标记 SENT，
     * 其他结果按退避时间重试，达到上限则 QUARANTINED。SENT 只表示发布确认，不能表示订单已完成。
     * 回写必须匹配本次令牌，避免租约过期后的旧任务覆盖新持有者的结果。
     *
     * @return 本轮成功由本次持有者回写为 SENT 的事件数
     */
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

    /**
     * 通过事件 ID、认领令牌和 CLAIMED 状态做条件回写；租约被其他任务接管后旧令牌更新不到记录。
     *
     * @param id
     *                   Outbox 事件 ID
     * @param token
     *                   本轮认领令牌
     * @param state
     *                   SENT、PENDING、DONE 或 QUARANTINED
     * @param result
     *                   发布结果或诊断原因
     * @param next
     *                   下次可处理的 Unix 毫秒
     *
     * @return 实际修改行数；0 表示本次已无回写资格等情况
     */
    private int finish(String id, String token, String state, String result, long next) {
        return outbox.finish(id, token, state, result, next);
    }
}
