package com.voucherx.commerce;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.commerce.mapper.*;
import com.voucherx.commerce.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class CommerceService {
    public enum Kind {
        PURCHASE, CONSUMPTION;
    }

    public record Receipt(String eventId, String outcome, String orderState) {
    }

    private final com.voucherx.recovery.FaultInjector faults;
    private final PurchaseOrderMapper purchases;
    private final ConsumptionOrderMapper consumptions;
    private final UserCouponMapper userCoupons;
    private final PaymentEventMapper payments;
    private final CouponActionMapper actions;
    private final Clock clock;
    private final CommerceProperties config;

    public CommerceService(PurchaseOrderMapper purchases, ConsumptionOrderMapper consumptions,
            UserCouponMapper userCoupons, PaymentEventMapper payments, CouponActionMapper actions, Clock clock,
            CommerceProperties config, com.voucherx.recovery.FaultInjector faults) {
        this.purchases = purchases;
        this.consumptions = consumptions;
        this.userCoupons = userCoupons;
        this.payments = payments;
        this.actions = actions;
        this.clock = clock;
        this.config = config;
        this.faults = faults;
    }

    /** 在创建秒杀订单的现有事务中调用，固定付款及权益规则快照。 */
    public void initializePurchase(long orderId, long voucherId) {
        var v = purchases.terms(voucherId);
        if (v == null)
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        if (v.payValue() < 0 || v.actualValue() <= 0 || v.minSpend() < 0
                || v.validitySeconds() < 1 || v.validitySeconds() > 315360000L)
            throw new BusinessException(ErrorCode.INVALID_PARAM, "优惠券金额或有效期配置不合法");
        purchases.initialize(orderId, v, clock.millis() + config.paymentTimeoutMillis());
    }

    @Transactional
    public long consume(long user, long shop, long couponId, long original) {
        // 所有路径按订单 -> 券加锁；新订单插入和锁券失败一起回滚。
        var preliminary = coupon(couponId, false);
        String token = UUID.randomUUID().toString();
        long now = clock.millis();
        long discount = discount(preliminary, user, shop, original, now);
        var draft = new ConsumptionOrder(0, user, shop, couponId, token, original, discount, original - discount,
                "PENDING_PAYMENT",
                Math.min(now + config.paymentTimeoutMillis(), preliminary.validUntilMs()), now);
        var key = new GeneratedId();
        consumptions.insert(draft, key);
        if (key.getId() == null)
            throw new IllegalStateException("数据库未返回主键");
        long id = key.getId();
        var c = coupon(couponId, true);
        discount(c, user, shop, original, now);
        int changed = userCoupons.tryLock(couponId, id, token, c.version());
        if (changed != 1)
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券已被其他订单锁定");
        action(couponId, id, token, "LOCK", now);
        faults.hit(com.voucherx.recovery.FaultPoint.COUPON_LOCKED, String.valueOf(id), token);
        return id;
    }

    private long discount(UserCoupon c, long user, long shop, long original, long now) {
        return CommerceRules.discount(c.userId(), user, c.shopId(), shop, c.validFromMs(),
                c.validUntilMs(), now, c.state(), c.minSpend(), c.faceValue(), original);
    }

    @Transactional
    public Receipt simulate(long user, Kind kind, long id, String eventId, long amount, boolean success) {
        if (!config.simulationEnabled())
            throw new BusinessException(ErrorCode.NOT_FOUND, "模拟支付未启用");
        if (eventId == null || !eventId.matches("[A-Za-z0-9._:-]{1,64}") || amount < 0)
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        var order = order(kind, id, user, true);
        if (amount != order.payAmount())
            throw new BusinessException(ErrorCode.CONFLICT, "付款金额与订单不一致");
        var old = payments.find(eventId);
        if (old != null) {
            if (!kind.name().equals(old.orderKind()) || old.orderId() != id || old.userId() != user
                    || old.amount() != amount || old.success() != success)
                throw new BusinessException(ErrorCode.CONFLICT, "同一支付事件内容冲突");
            return new Receipt(eventId, old.outcome(), old.orderState());
        }
        long now = clock.millis();
        String state = order.paymentStatus();
        String outcome = CommerceRules.paymentOutcome(state, order.paymentDeadlineMs(), now, success);
        if ("APPLIED".equals(outcome)) {
            if (kind == Kind.PURCHASE)
                issue((PurchaseOrder) order, now);
            else
                redeem((ConsumptionOrder) order, now);
            if ((kind == Kind.PURCHASE ? purchases.markPaid(id) : consumptions.markPaid(id)) != 1)
                throw new BusinessException(ErrorCode.CONFLICT);
            state = "PAID";
        } else if ("CONFLICT_EXPIRED".equals(outcome)) {
            closeLocked(kind, order, now);
            state = "CANCELLED";
        }
        payments.insert(new PaymentEvent(eventId, kind.name(), id, user, amount, success, outcome, state, now));
        faults.hit(com.voucherx.recovery.FaultPoint.PAYMENT_BEFORE_COMMIT, String.valueOf(id), eventId);
        return new Receipt(eventId, outcome, state);
    }

    private void issue(PurchaseOrder order, long now) {
        userCoupons.issue(order, now, Math.addExact(now, Math.multiplyExact(order.couponValiditySeconds(), 1000)));
    }

    private void redeem(ConsumptionOrder order, long now) {
        long couponId = order.couponId();
        var c = coupon(couponId, true);
        requireLock(order, c);
        if (now < c.validFromMs() || now >= c.validUntilMs())
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券已过期");
        userCoupons.use(couponId);
        action(couponId, order.id(), order.lockToken(), "USE", now);
    }

    @Transactional
    public String cancel(long user, Kind kind, long id) {
        var order = order(kind, id, user, true);
        String state = order.paymentStatus();
        if ("CANCELLED".equals(state))
            return state;
        if (!"PENDING_PAYMENT".equals(state))
            throw new BusinessException(ErrorCode.CONFLICT, "当前订单不允许取消");
        closeLocked(kind, order, clock.millis());
        return "CANCELLED";
    }

    @Transactional
    public void expire(Kind kind, long id) {
        var order = readOrder(kind, id, true);
        if (order == null)
            return;
        long now = clock.millis();
        if ("PENDING_PAYMENT".equals(order.paymentStatus()) && now >= order.paymentDeadlineMs())
            closeLocked(kind, order, now);
    }

    private void closeLocked(Kind kind, CommerceOrder order, long now) {
        if (kind == Kind.CONSUMPTION) {
            var consumption = (ConsumptionOrder) order;
            var c = coupon(consumption.couponId(), true);
            requireLock(consumption, c);
            userCoupons.release(c.id(), CommerceRules.releasedState(c.validUntilMs(), now));
            action(c.id(), order.id(), consumption.lockToken(), "RELEASE", now);
        }
        if (kind == Kind.PURCHASE)
            purchases.cancel(order.id());
        else
            consumptions.cancel(order.id());
    }

    private void requireLock(ConsumptionOrder order, UserCoupon coupon) {
        if (!"LOCKED".equals(coupon.state()) || coupon.lockOrderId() == null
                || coupon.lockOrderId() != order.id() || !order.lockToken().equals(coupon.lockToken())
                || coupon.userId() != order.userId())
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券锁归属不匹配");
    }

    private void action(long coupon, long order, String token, String action, long now) {
        actions.insert(coupon, order, token, action, now);
    }

    public CommerceOrder order(Kind kind, long id, long user, boolean lock) {
        var order = readOrder(kind, id, lock);
        if (order == null || order.userId() != user)
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        return order;
    }

    private CommerceOrder readOrder(Kind kind, long id, boolean lock) {
        return kind == Kind.PURCHASE ? (lock ? purchases.lock(id) : purchases.find(id))
                : (lock ? consumptions.lock(id) : consumptions.find(id));
    }

    public UserCoupon coupon(long id, boolean lock) {
        var coupon = lock ? userCoupons.lock(id) : userCoupons.find(id);
        if (coupon == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在");
        return coupon;
    }

    public List<UserCoupon> coupons(long user, long after, int size) {
        if (after < 0 || size < 1 || size > 100)
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        return userCoupons.page(user, after, size);
    }

    public List<Long> expiredOrderIds(Kind kind, long now, int limit) {
        return kind == Kind.PURCHASE ? purchases.expiredIds(now, limit) : consumptions.expiredIds(now, limit);
    }
}
