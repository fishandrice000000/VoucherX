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
 * 03 的发送执行者：HTTP 已提交受理和待发事件后，由调度线程接续发送。
 * <p>
 * 扫描、认领和结果回写是独立数据库操作，等待 Broker Confirm 时不持有包住全流程的数据库事务。
 * 租约到期允许接手，却不终止旧线程或撤回其网络发送；重复消息靠账本和订单幂等处理。
 * <p>
 * SENT 是发布结果，SUCCEEDED 是建单结果，消费提交可能早于本线程写 SENT。
 * QUARANTINED 也只是投递隔离，不能直接映射成用户业务 FAILED 或返还 Redis 库存。
 *
 * @see RequestLedger#accept
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
     * 按配置固定延迟调用 dispatch；enabled=false 时跳过自动投递，扫描异常记日志，已认领事件可在租约到期后重新成为候选。
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
     * 完成一轮有界发送：本轮起始时读取 now，扫描到期 PENDING 或租约过期 CLAIMED 的事件。
     * <ol>
     * <li>候选 SQL 同时要求活动 NORMAL、请求 ACCEPTED 且代次匹配。</li>
     * <li>用新 UUID claim_token 条件认领，成功时 attempts 加 1、租约设为
     * now+leaseMillis；未更新到行则跳过。</li>
     * <li>读取请求，已终止则尝试标 DONE；发送前普通查询门禁，不正常则延后，最终执行资格仍由消费者事务守卫确认。</li>
     * <li>构造同一个 R1/E1 的消息并等待 Confirm；CONFIRMED 尝试写 SENT，其余结果按预算写 PENDING 或
     * QUARANTINED。</li>
     * </ol>
     * <p>
     * 重试时间和租约用本轮起始 now 计算，不是每次网络等待完成后重新取时刻；慢扫描时不能据此推断还剩完整租期。
     * finish 必须匹配本次令牌，旧认领者即使仍发送了消息也不能覆盖接手者的回写。
     * 显式调用本方法不检查 enabled，测试可关闭自动扫描后手动触发。
     *
     * @return 本轮本次令牌成功回写 SENT 的行数；不是已完成订单数，也不是所有实际到达队列的消息数
     * @see com.voucherx.fulfillment.mapper.OutboxMapper#dueEvents
     * @see com.voucherx.fulfillment.mapper.OutboxMapper#claim
     * @see RequestRecord#message()
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
     * 用 eventId、claim_token 和 CLAIMED 状态条件更新发布结果并清空令牌。
     * <p>
     * SQL 不单独要求租约仍未到期；关键是尚未被新的令牌或其他处理改写。
     * 租约已到期但未被接手时旧令牌仍可能回写；一旦换了新令牌，旧持有者更新数为 0。
     *
     * @param id
     *                   固定 Outbox 事件 ID
     * @param token
     *                   本轮认领随机令牌
     * @param state
     *                   SENT、PENDING、DONE 或 QUARANTINED
     * @param result
     *                   发布结果或诊断原因
     * @param next
     *                   下次允许处理的 Unix 毫秒
     * @return 实际修改行数；0 表示本次条件已不匹配
     */
    private int finish(String id, String token, String state, String result, long next) {
        return outbox.finish(id, token, state, result, next);
    }
}
