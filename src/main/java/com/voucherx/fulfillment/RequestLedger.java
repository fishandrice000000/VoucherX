package com.voucherx.fulfillment;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.order.service.OrderBusinessException;
import com.voucherx.order.service.OrderFailure;
import com.voucherx.fulfillment.mapper.SeckillRequestMapper;
import com.voucherx.fulfillment.mapper.OutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class RequestLedger {
    private final SeckillRequestMapper requests;
    private final OutboxMapper outbox;
    private final ActivityGate gate;
    private final Clock clock;
    private final DeliveryProperties config;

    public RequestLedger(SeckillRequestMapper requests, OutboxMapper outbox, ActivityGate gate, Clock clock,
            DeliveryProperties config) {
        this.requests = requests;
        this.outbox = outbox;
        this.gate = gate;
        this.clock = clock;
        this.config = config;
    }

    public RequestRecord find(String id) {
        return requests.find(id);
    }

    public RequestRecord byKey(long userId, String key) {
        return requests.byKey(userId, key);
    }

    @Transactional
    public RequestRecord intent(long user, long voucher, String key, String mode, int bucket, int layout) {
        long epoch = gate.requireNormal(voucher);
        String id = UUID.randomUUID().toString();
        long now = clock.millis();
        requests.insertIntent(id, UUID.randomUUID().toString(), user, voucher, key, epoch, mode, bucket, layout, now);
        return find(id);
    }

    public RequestRecord lock(String id) {
        RequestRecord record = requests.lock(id);
        if (record == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        return record;
    }

    @Transactional
    public RequestRecord accept(String id) {
        RequestRecord before = find(id);
        if (before == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        long epoch = gate.requireNormal(before.voucherId());
        RequestRecord r = lock(id);
        if (r.epoch() != epoch)
            throw new BusinessException(ErrorCode.CONFLICT, "请求恢复代次已失效");
        if ("INTENT".equals(r.phase())) {
            requests.accept(id, clock.millis());
            outbox.insert(r.eventId(), id, clock.millis());
        }
        return find(id);
    }

    /** 调用者必须处于扣库存/建单的同一事务中。 */
    public RequestRecord guard(SeckillOrderMessage message) {
        var c = gate.lock(message.voucherId());
        if (find(message.requestId()) == null)
            throw new OrderBusinessException(OrderFailure.INVALID_REQUEST, "无持久化受理依据");
        RequestRecord r = lock(message.requestId());
        if (!r.matches(message))
            throw new OrderBusinessException(OrderFailure.INVALID_REQUEST, "消息身份/布局/代次不匹配");
        if (!"NORMAL".equals(c.state()) || c.epoch() != message.epoch())
            throw new StaleDeliveryException();
        if ("CLOSED".equals(r.phase()))
            throw new OrderBusinessException(OrderFailure.REQUEST_CLOSED, "请求已经关闭");
        if (!"ACCEPTED".equals(r.phase()) && !"SUCCEEDED".equals(r.phase()))
            throw new StaleDeliveryException();
        return r;
    }

    public void succeed(String id, long orderId) {
        requests.succeed(id, orderId, clock.millis());
    }

    @Transactional
    public void rejectUnreserved(String id, String reason) {
        RequestRecord before = find(id);
        gate.lock(before.voucherId());
        RequestRecord r = lock(id);
        if ("INTENT".equals(r.phase()))
            requests.rejectUnreserved(id, reason, clock.millis());
    }

    @Transactional
    public boolean fail(SeckillOrderMessage message, String reason) {
        var c = gate.lock(message.voucherId());
        if (find(message.requestId()) == null)
            return false;
        RequestRecord r = lock(message.requestId());
        if (!r.matches(message) || !"NORMAL".equals(c.state()) || c.epoch() != r.epoch())
            return false;
        if ("SUCCEEDED".equals(r.phase()))
            return false;
        if ("ACCEPTED".equals(r.phase()))
            requests.close(r.requestId(), reason, clock.millis());
        return true;
    }

    @Transactional
    public void defer(SeckillOrderMessage message, String reason) {
        var c = gate.lock(message.voucherId());
        RequestRecord r = lock(message.requestId());
        if (!r.matches(message) || r.terminal() || c.epoch() != r.epoch() || !"NORMAL".equals(c.state()))
            return;
        outbox.defer(r.requestId(), config.maxAttempts(), clock.millis() + config.retryMillis(), reason);
    }

    public void quarantine(String id, String reason) {
        requests.recordReason(id, reason, clock.millis());
        outbox.quarantine(id, reason);
    }
}
