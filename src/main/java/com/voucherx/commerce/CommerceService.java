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

/**
 * 从“抢到购买资格”继续到“付款拿券、消费用券”的交易服务。
 * <p>
 * 购买订单由秒杀消费者创建，initializePurchase 在建单事务中固定金额和权益规则。
 * 购买付款在 simulate 的单个事务里完成发券、改为 PAID 和保存支付事件；
 * 消费下单在 consume 的事务里插消费单并锁券，消费付款再在 simulate 的事务里核销。
 * <p>
 * 金额统一使用分，绝对时刻使用 Unix 毫秒，权益有效时长使用秒。
 * 需要同时操作订单和券时统一按“订单 → 券”加锁，支付事件幂等键与抢券幂等键分别管理。
 */
@Service
public class CommerceService {
    /**
     * 区分买券订单与用券消费订单；两者可能有相同数值 ID，定位时必须同时带上类型。
     */
    public enum Kind {
        /**
         * 买券：付款成功后生成用户券。
         */
        PURCHASE,
        /**
         * 消费：下单锁住已有用户券，付款成功后核销。
         */
        CONSUMPTION;
    }

    /**
     * 支付事件的处理回执；重放同一事件时返回原先保存的结果。
     *
     * @param eventId
     *                       支付事件幂等键
     * @param outcome
     *                       APPLIED、FAILED_ATTEMPT、ALREADY_PAID 或冲突等结果
     * @param orderState
     *                       本事件处理后保存的付款状态；重放时是原事件快照
     */
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

    /**
     * 在调用者已有的秒杀建单事务中读取券模板，将买券价格、面额、商户、门槛和有效时长固定到购买订单。
     * <p>
     * 同时设置 PENDING_PAYMENT 和付款截止时刻；以后发券按订单快照执行，不重新读取模板。
     * 本方法自身不开事务，也尚未付款或创建用户券。
     *
     * @param orderId
     *                      刚插入并取得自增主键的购买订单 ID
     * @param voucherId
     *                      订单对应的券模板/活动 ID
     */
    public void initializePurchase(long orderId, long voucherId) {
        var v = purchases.terms(voucherId);
        if (v == null)
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        if (v.payValue() < 0 || v.actualValue() <= 0 || v.minSpend() < 0
                || v.validitySeconds() < 1 || v.validitySeconds() > 315360000L)
            throw new BusinessException(ErrorCode.INVALID_PARAM, "优惠券金额或有效期配置不合法");
        purchases.initialize(orderId, v, clock.millis() + config.paymentTimeoutMillis());
    }

    /**
     * 创建消费订单并将用户券 AVAILABLE → LOCKED，两项写入在同一事务中提交或回滚。
     * <p>
     * 先普通读券校验并计算优惠，再插入待付消费单取得自增 ID；随后锁券行、复核归属和可用性，
     * 按状态及 version 条件绑定消费单 ID 与随机 lockToken，并写 LOCK 动作。
     * <p>
     * 例如原价 10000 分、面额 2000 分，优惠 2000 分，应付 8000 分。
     * 付款截止取配置超时和券失效时刻的较早值；锁券只保留使用资格，核销要等消费付款成功。
     *
     * @param user
     *                     当前登录用户 ID
     * @param shop
     *                     实际消费商户 ID
     * @param couponId
     *                     用户券 ID，不是模板或活动 ID
     * @param original
     *                     消费原价，单位分
     *
     * @return 新消费订单 ID；经事务代理正常返回时订单与锁券均已提交
     */
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

    /**
     * 将用户券快照交给纯规则函数，检查归属、商户、时间、状态及门槛并计算优惠。
     *
     * @param c
     *                     用户券快照
     * @param user
     *                     当前用户 ID
     * @param shop
     *                     消费商户 ID
     * @param original
     *                     消费原价，分
     * @param now
     *                     本次操作时刻，Unix 毫秒
     *
     * @return 不超过原价的实际优惠额，分
     */
    private long discount(UserCoupon c, long user, long shop, long original, long now) {
        return CommerceRules.discount(c.userId(), user, c.shopId(), shop, c.validFromMs(),
                c.validUntilMs(), now, c.state(), c.minSpend(), c.faceValue(), original);
    }

    /**
     * 处理一个模拟支付事件，订单行锁串行化同一订单的付款及关闭操作。
     * <p>
     * 先要求模拟开关开启、事件格式合法、用户归属和金额匹配。同 eventId 已存在且内容相同，
     * 返回原回执；内容冲突则拒绝。成功且未超时的待付订单进入 APPLIED：购买单发券，消费单核销，
     * 随后改订单为 PAID 并保存支付事件，全部在同一 MySQL 事务中完成。
     * <p>
     * success=false 只记录 FAILED_ATTEMPT，不关闭订单；迟到的成功付款会走过期关闭分支。
     * 订单已付款、已取消等结果也记录回执，outcome 描述事件是否实际应用，不能只看 success 输入。
     * 本入口模拟支付结果，不会向外部支付机构扣款。
     *
     * @param user
     *                    当前登录用户 ID
     * @param kind
     *                    PURCHASE 买券或 CONSUMPTION 消费
     * @param id
     *                    相应类型的订单 ID
     * @param eventId
     *                    支付事件幂等键，同事件重试需复用
     * @param amount
     *                    事件金额，分，必须等于订单应付
     * @param success
     *                    此次模拟事件声明的支付成功与否
     *
     * @return 处理结果及订单付款状态；经事务代理正常返回时相关数据库写入已提交
     */
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

    /**
     * 按购买订单权益快照生成 AVAILABLE 用户券；有效期从本次成功付款时刻开始计算。
     * <p>
     * 在 simulate 的同一事务内调用，购买订单唯一约束配合付款状态、事件幂等避免重复发券。
     *
     * @param order
     *                  已锁定、待付款的购买订单快照
     * @param now
     *                  本次付款处理时刻，Unix 毫秒
     */
    private void issue(PurchaseOrder order, long now) {
        userCoupons.issue(order, now, Math.addExact(now, Math.multiplyExact(order.couponValiditySeconds(), 1000)));
    }

    /**
     * 锁住消费单关联的券，核对用户、锁单 ID、锁令牌及有效期，再置为 USED 并写 USE 动作。
     * <p>
     * 在 simulate 已持有订单锁的同一事务中执行，核销、改订单付款状态与支付事件一起提交。
     *
     * @param order
     *                  已锁定、待付款的消费订单
     * @param now
     *                  本次付款处理时刻，Unix 毫秒
     */
    private void redeem(ConsumptionOrder order, long now) {
        long couponId = order.couponId();
        var c = coupon(couponId, true);
        requireLock(order, c);
        if (now < c.validFromMs() || now >= c.validUntilMs())
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券已过期");
        userCoupons.use(couponId);
        action(couponId, order.id(), order.lockToken(), "USE", now);
    }

    /**
     * 主动取消用户拥有的待付订单；已取消时幂等返回，已付款等其他状态拒绝。
     * <p>
     * 消费单同时释放自己的券锁；购买单未付款则不发券，也没有在这里回补秒杀库存或删除一人一单资格。
     *
     * @param user
     *                 当前登录用户 ID
     * @param kind
     *                 订单类型
     * @param id
     *                 对应类型的订单 ID
     *
     * @return 正常关闭后的 CANCELLED 状态
     */
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

    /**
     * 定时任务逐单调用的事务入口；锁订单后重新检查待付状态及截止时间，避免扫描与付款并发时误关闭。
     *
     * @param kind
     *                 扫描出的订单类型
     * @param id
     *                 候选超时订单 ID；不存在时忽略
     */
    @Transactional
    public void expire(Kind kind, long id) {
        var order = readOrder(kind, id, true);
        if (order == null)
            return;
        long now = clock.millis();
        if ("PENDING_PAYMENT".equals(order.paymentStatus()) && now >= order.paymentDeadlineMs())
            closeLocked(kind, order, now);
    }

    /**
     * 在已持有订单锁的事务中关闭订单；消费单先锁券并核对归属，再清除券锁、增加版本并写 RELEASE。
     * <p>
     * 释放后的券按当前时刻变为 AVAILABLE 或 EXPIRED；购买单只更新付款状态，不回补秒杀库存。
     * 调用者须已确认订单仍为待付状态，不能把此方法用于已付款退款。
     *
     * @param kind
     *                  订单类型
     * @param order
     *                  已加锁且允许关闭的订单
     * @param now
     *                  关闭时刻，Unix 毫秒
     */
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

    /**
     * 核对券是否处于 LOCKED，且用户、lockOrderId、lockToken 都属于当前消费单，防止旧订单误用或误释放新锁。
     *
     * @param order
     *                   已锁定的消费订单
     * @param coupon
     *                   已锁定的用户券快照
     */
    private void requireLock(ConsumptionOrder order, UserCoupon coupon) {
        if (!"LOCKED".equals(coupon.state()) || coupon.lockOrderId() == null
                || coupon.lockOrderId() != order.id() || !order.lockToken().equals(coupon.lockToken())
                || coupon.userId() != order.userId())
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券锁归属不匹配");
    }

    /**
     * 写入 LOCK、USE 或 RELEASE 券动作流水；事务由消费下单、付款或关闭调用者提供。
     *
     * @param coupon
     *                   用户券 ID
     * @param order
     *                   消费订单 ID
     * @param token
     *                   本轮锁券令牌
     * @param action
     *                   券动作类型
     * @param now
     *                   动作时刻，Unix 毫秒
     */
    private void action(long coupon, long order, String token, String action, long now) {
        actions.insert(coupon, order, token, action, now);
    }

    /**
     * 读取指定类型订单并检查用户归属，其他用户的订单按不存在处理。
     * <p>
     * lock=true 时调用方须已有事务，才能把行锁保持到付款或关闭完成。
     *
     * @param kind
     *                 订单类型
     * @param id
     *                 相应类型的订单 ID
     * @param user
     *                 当前用户 ID
     * @param lock
     *                 是否使用 FOR UPDATE
     *
     * @return 属于当前用户的订单快照
     */
    public CommerceOrder order(Kind kind, long id, long user, boolean lock) {
        var order = readOrder(kind, id, lock);
        if (order == null || order.userId() != user)
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        return order;
    }

    /**
     * 按类型选择购买单或消费单 Mapper，按需使用 FOR UPDATE；只负责读取，不校验用户归属。
     *
     * @param kind
     *                 订单类型
     * @param id
     *                 对应类型的订单 ID
     * @param lock
     *                 是否请求行锁；事务由调用者提供
     *
     * @return 订单；不存在时为 {@code null}
     */
    private CommerceOrder readOrder(Kind kind, long id, boolean lock) {
        return kind == Kind.PURCHASE ? (lock ? purchases.lock(id) : purchases.find(id))
                : (lock ? consumptions.lock(id) : consumptions.find(id));
    }

    /**
     * 按需加锁读取用户券；此方法只校验存在性，归属及业务状态由调用方进一步验证。
     *
     * @param id
     *                 用户券 ID
     * @param lock
     *                 是否使用 FOR UPDATE；为 true 时调用方须已有事务
     *
     * @return 用户券快照；不存在时抛业务错误
     */
    public UserCoupon coupon(long id, boolean lock) {
        var coupon = lock ? userCoupons.lock(id) : userCoupons.find(id);
        if (coupon == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在");
        return coupon;
    }

    /**
     * 校验游标及页大小后，查询当前用户 ID 大于游标的券，按 ID 递增返回。
     *
     * @param user
     *                  当前用户 ID
     * @param after
     *                  上一页最后一个券 ID；首页为 0
     * @param size
     *                  每页 1 到 100 条
     *
     * @return 券列表；没有更多记录时为空列表
     */
    public List<UserCoupon> coupons(long user, long after, int size) {
        if (after < 0 || size < 1 || size > 100)
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        return userCoupons.page(user, after, size);
    }

    /**
     * 查询到期且仍待付的候选订单；这里不加行锁，真正关闭前 expire 会在事务里重新确认。
     *
     * @param kind
     *                  需要扫描的订单类型
     * @param now
     *                  扫描时刻，Unix 毫秒
     * @param limit
     *                  本轮最多返回条数
     *
     * @return 候选订单 ID，按付款截止时间排序
     */
    public List<Long> expiredOrderIds(Kind kind, long now, int limit) {
        return kind == Kind.PURCHASE ? purchases.expiredIds(now, limit) : consumptions.expiredIds(now, limit);
    }
}
