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
 * 04 的交易编排，接续 03 已建好的购买单，完成买券付款、用户券发行、消费锁券、核销和关闭。
 * <p>
 * initializePurchase 在建单现有事务中固定规则；simulate 在交易事务中联合发券/核销、付款状态和支付事件。
 * consume 联合插消费单、绑定券锁和 LOCK 动作；取消/到期则联合关闭消费单与 RELEASE 动作。
 * <p>
 * 金额为分，绝对时刻为 Unix 毫秒，有效时长为秒。操作订单及券时统一按订单 → 券加锁；
 * lockToken 标识哪一轮消费锁归属，version 支持条件更新，两者都不等于数据库行锁本身。
 * <p>
 * 带 Transactional 的公开方法使用默认 REQUIRED：HTTP/任务调用没有外层事务时由本次调用提交，已有事务则加入外层。
 * 这是本地模拟支付协议，没有第三方资金清算和已付款退款流程。
 *
 * @see CommerceController
 * @see CommerceRules
 * @see CommerceExpiryJob
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
     * 支付事件的历史回执，字段描述该事件当次处理的结果。
     * <p>
     * 相同 eventId 重放返回原快照；例如失败事件记录 PENDING_PAYMENT 后订单再被取消，重放失败事件仍可返回原
     * PENDING_PAYMENT。
     * 查询订单当前状态应走 order，不把回执快照当作实时查询。
     *
     * @param eventId
     *                       本次或被重放的支付事件幂等键
     * @param outcome
     *                       APPLIED、FAILED_ATTEMPT、ALREADY_PAID 或冲突分类
     * @param orderState
     *                       此事件原先处理后保存的订单付款状态
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
     * 在新购买单创建的现有事务中读取模板，把买券价格、券面额、商户、门槛及有效时长固定到订单。
     * <p>
     * 例如模板买价 1000 分、面额 2000 分、门槛 5000 分、有效 604800 秒，付款发券以后只按这份订单快照解释权益。
     * 验证金额和有效时长后，设置 PENDING_PAYMENT 与当前业务时刻加配置超时的截止（默认约 15 分钟）。
     * 本方法无事务注解，依赖建单调用者；尚未付款，也未生成用户券。
     * <p>
     * Mapper 只按订单 ID 更新，没有旧状态条件，调用前提是刚创建的新单，不能把它当作重置历史付款状态的接口。
     *
     * @param orderId
     *                      本次刚插入的购买订单 ID
     * @param voucherId
     *                      该订单绑定的券模板/活动 ID
     * @see com.voucherx.order.service.OrderPersistenceService#persist
     * @see com.voucherx.commerce.mapper.PurchaseOrderMapper#initialize
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
     * 在同一事务中创建待付消费单、把用户券 AVAILABLE→LOCKED 并写 LOCK 动作。
     * <ol>
     * <li>普通读券，按本次捕获的 now 初验归属、商户、有效期、状态及门槛，计算 min(面额, 原额)。</li>
     * <li>生成随机 lockToken，插消费单保存原额、优惠、应付及令牌，通过 GeneratedId 取得真实自增订单 ID。</li>
     * <li>再 FOR UPDATE 锁券，用同一个 now 复核可用条件；按 AVAILABLE 和 version 条件绑定订单 ID/令牌，更新必须为
     * 1 行。</li>
     * <li>写 LOCK 动作后由事务提交；锁券或后续步骤失败，已插消费单也回滚。</li>
     * </ol>
     * <p>
     * 例如券 4001 面额 2000 分，原额 10000 分，优惠 2000、应付 8000；截止取配置超时与券失效时刻较早者。
     * now 在初读后捕获，锁后校验复用它，不是锁后重新读取时钟；后续付款仍会使用自己的时间检查期限。
     * <p>
     * 插订单后锁券与付款/关闭的订单 → 券顺序一致。锁定只保留这张券的使用归属，券尚未 USED，也不再扣秒杀库存。
     *
     * @param user
     *                     当前登录用户 ID
     * @param shop
     *                     消费商户 ID
     * @param couponId
     *                     用户券 ID，不是活动 ID
     * @param original
     *                     原消费金额，分，当前简化模型由请求传入
     * @return 新消费订单 ID；最终提交取决于当前最外层事务边界
     * @see CommerceRules#discount
     * @see com.voucherx.commerce.mapper.ConsumptionOrderMapper#insert
     * @see com.voucherx.commerce.mapper.UserCouponMapper#tryLock
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
     * @return 不超过原价的实际优惠额，分
     */
    private long discount(UserCoupon c, long user, long shop, long original, long now) {
        return CommerceRules.discount(c.userId(), user, c.shopId(), shop, c.validFromMs(),
                c.validUntilMs(), now, c.state(), c.minSpend(), c.faceValue(), original);
    }

    /**
     * 在订单锁保护下处理一个模拟支付事件：先检查开关/格式，再核对订单归属和金额，然后处理幂等及状态分支。
     * <p>
     * 相同 eventId 已有记录时，逐项核对类型、订单、用户、金额和 success；相同返回原回执，冲突抛错。
     * 同订单行锁串行化它的付款和关闭，全局 eventId 主键另为跨订单的事件碰撞兜底。
     * <ul>
     * <li>APPLIED：购买单 issue，消费单 redeem，再条件写 PAID，必须更新 1 行。</li>
     * <li>CONFLICT_EXPIRED：关闭仍待付的超时订单，消费单还释放自己的券锁。</li>
     * <li>其他结果：只保存本事件回执，保留当前订单状态。</li>
     * </ul>
     * <p>
     * 发券/核销或关闭、订单状态和支付事件在一个事务里提交或回滚。success=false 优先得到 FAILED_ATTEMPT，
     * 即使订单已过截止，也不在此失败分支主动关闭；PAID 收到新的成功事件得到 ALREADY_PAID，取消单收到迟到成功得到
     * CONFLICT_TERMINAL。
     * <p>
     * 新事件按新的 now 决策，旧事件重放不重新解释原 outcome/orderState。默认模拟开关关闭，入口返回业务拒绝。
     *
     * @param user
     *                    当前登录用户 ID
     * @param kind
     *                    非空订单类型 PURCHASE 或 CONSUMPTION
     * @param id
     *                    该类型中的订单 ID
     * @param eventId
     *                    全局支付事件幂等键，1 到 64 个允许字符
     * @param amount
     *                    声明支付金额，分，必须等于订单应付
     * @param success
     *                    本次模拟事件的支付成功声明
     * @return 本事件处理结果；重放时为历史回执，提交受最外层事务边界决定
     * @see CommerceRules#paymentOutcome
     * @see #issue
     * @see #redeem
     * @see #closeLocked
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
     * 按购买单保存的权益快照发行一张 AVAILABLE 用户券，validFrom 为本次付款处理 now。
     * <p>
     * validUntil 用有效秒数乘 1000 再加 now，使用精确整数运算检查溢出；不重新读取活动当前模板。
     * 来源购买单唯一约束防止重复发行，调用者还持有购买单锁并核验待付状态。
     * 此插入与 PAID、支付事件在 simulate 同一事务中完成，失败一起回滚。
     *
     * @param order
     *                  已持锁、允许应用付款的购买单快照
     * @param now
     *                  本次付款处理 Unix 毫秒
     * @see com.voucherx.commerce.mapper.UserCouponMapper#issue
     */
    private void issue(PurchaseOrder order, long now) {
        userCoupons.issue(order, now, Math.addExact(now, Math.multiplyExact(order.couponValiditySeconds(), 1000)));
    }

    /**
     * 在调用方已锁消费单的事务中再锁用户券，requireLock 核对状态、消费单 ID、令牌及用户，随后检查有效期。
     * <p>
     * 通过后将 LOCKED→USED、version 加一，并写 USE 动作；订单 PAID 和支付事件由外层 simulate 一起写入。
     * SQL 不再次比较完整锁归属，是因为检查到更新之间仍持有券行锁。
     * 核销不清除历史锁字段，也不会再次扣活动发行库存。
     *
     * @param order
     *                  已持行锁的待付消费单
     * @param now
     *                  本次付款处理 Unix 毫秒
     * @see #requireLock
     * @see com.voucherx.commerce.mapper.UserCouponMapper#use
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
     * 锁住当前用户拥有的订单，再决定是否允许取消，和付款共用同一订单行锁。
     * <p>
     * 已经 CANCELLED 直接返回，不再读取或释放券；只有 PENDING_PAYMENT 才进入 closeLocked，PAID/历史状态等拒绝。
     * 例如 5001 取消后券被 5002/L2 重新锁住，旧 5001 重复取消直接返回，不能误释放新锁。
     * <p>
     * 消费取消联合释放券与关闭消费单；购买取消尚未发行用户券，也不会在这里返还活动库存或清除领取资格。
     *
     * @param user
     *                 当前登录用户 ID
     * @param kind
     *                 非空订单类型
     * @param id
     *                 对应类型的订单 ID
     * @return 正常关闭或幂等重放的 CANCELLED
     * @see #closeLocked
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
     * 供到期任务逐笔调用的事务入口，先锁订单，再用当前业务时间复核仍待付且 now 已到付款截止。
     * <p>
     * 扫描只是候选，此处复核可避免把已经被付款提交的订单关掉；已不存在或不满足条件就返回。
     * 这条内部任务路径不依赖 HTTP 用户上下文，和主动取消共用 closeLocked。
     *
     * @param kind
     *                 候选订单的非空类型
     * @param id
     *                 候选订单 ID
     * @see CommerceExpiryJob#tick()
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
     * 调用者已持有待付订单锁时，联合完成取消或到期关闭。
     * <p>
     * 消费单再锁券并核对归属，按 now 与 validUntil 选择 AVAILABLE/EXPIRED，清空锁字段、增加版本并写 RELEASE，
     * 再条件写消费单 CANCELLED；购买单只条件写 CANCELLED。
     * <p>
     * 假设付款先获得订单锁并提交 PAID，取消将看到终态而不进入此处；取消先提交时，新成功付款只记录终态冲突。
     * 订单、券及动作在调用者同一事务中提交，方法本身不开新事务，也不实现退款或活动发行额度补偿。
     *
     * @param kind
     *                  订单类型
     * @param order
     *                  已加锁且调用者确认允许关闭的订单
     * @param now
     *                  本次关闭判断使用的 Unix 毫秒
     * @see #requireLock
     * @see CommerceRules#releasedState
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
     * 在仍持有订单和券行锁时核对券的 LOCKED 状态，以及用户、lockOrderId 和 lockToken 三项归属。
     * <p>
     * 例如消费单 5001/L1 只能核销或释放券上同一组 5001/L1；后续 5002/L2 的锁不能被旧路径误操作。
     * 令牌是服务端每次消费下单生成的随机归属标识，不是登录 Token；version 另用于条件更新。
     * 本方法只验证快照，不获取数据库锁，调用者须让检查到 SQL 更新之间保持事务行锁。
     *
     * @param order
     *                   当前已锁定的消费单
     * @param coupon
     *                   当前已锁定的用户券快照
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             任一锁归属不匹配
     */
    private void requireLock(ConsumptionOrder order, UserCoupon coupon) {
        if (!"LOCKED".equals(coupon.state()) || coupon.lockOrderId() == null
                || coupon.lockOrderId() != order.id() || !order.lockToken().equals(coupon.lockToken())
                || coupon.userId() != order.userId())
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券锁归属不匹配");
    }

    /**
     * 写 LOCK、USE 或 RELEASE 动作，携带用户券、消费单和本轮令牌；与对应交易一起提交，不在此处异步投递。
     *
     * @param coupon
     *                   用户券 ID
     * @param order
     *                   消费订单 ID
     * @param token
     *                   本轮券锁归属令牌
     * @param action
     *                   LOCK、USE 或 RELEASE
     * @param now
     *                   动作 Unix 毫秒
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
     * @return 用户券快照；不存在时抛业务错误
     */
    public UserCoupon coupon(long id, boolean lock) {
        var coupon = lock ? userCoupons.lock(id) : userCoupons.find(id);
        if (coupon == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在");
        return coupon;
    }

    /**
     * 按用户 ID 过滤，再取用户券 id 大于 after 的一页并按券 id 递增返回。
     * <p>
     * 这是券 ID 游标，不能把 after 当成用户 ID 或页码；当前查询不按 AVAILABLE 等状态过滤，结果也可能包含已锁、已用或已过期券。
     *
     * @param user
     *                  当前登录用户 ID
     * @param after
     *                  上一页最后一张用户券 ID，首页为 0
     * @param size
     *                  页大小，1 到 100
     * @return 用户自己的券列表；没有更多时为空列表
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
     * @return 候选订单 ID，按付款截止时间排序
     */
    public List<Long> expiredOrderIds(Kind kind, long now, int limit) {
        return kind == Kind.PURCHASE ? purchases.expiredIds(now, limit) : consumptions.expiredIds(now, limit);
    }
}
