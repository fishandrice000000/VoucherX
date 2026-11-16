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
 * 接续已创建的购买订单，完成付款发券、消费锁券、核销及订单关闭。
 * <p>
 * initializePurchase 在建单事务内固定价格和权益；simulatePayment 联合处理付款、权益和支付事件。
 * createConsumptionOrder 联合插入消费单、绑定券锁和 LOCK 动作；取消和到期联合关闭订单与释放券锁。
 * <p>
 * 金额单位为分，绝对时间为 Unix 毫秒，有效时长为秒。写入按订单、用户券顺序加锁，lockToken 标识一次消费锁的归属。
 * 公开事务方法采用 REQUIRED，独立调用在代理返回时提交，外层已有事务时共同提交。
 * 付款通过本地模拟事件协议驱动。
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
    public enum OrderKind {
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
     * 支付事件首次处理完成时保存的结果回执。
     * <p>
     * 相同 eventId 且内容一致时返回该快照；例如事件保存 PENDING_PAYMENT 后订单被取消，重放仍返回首次回执。
     * 订单当前状态通过 order 查询。
     *
     * @param eventId
     *                       本次或被重放的支付事件幂等键
     * @param outcome
     *                       APPLIED、FAILED_ATTEMPT、ALREADY_PAID 或冲突分类
     * @param orderState
     *                       此事件原先处理后保存的订单付款状态
     */
    public record PaymentReceipt(String eventId, String outcome, String orderState) {
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
     * 在购买单创建事务内读取模板，将价格、券面额、商户、门槛及有效时长固定到订单。
     * <p>
     * 校验金额和有效时长后，设置 PENDING_PAYMENT，并以当前时刻加配置付款超时计算截止时间，默认约 15 分钟。
     * 例如买价 1000 分、面额 2000 分、门槛 5000 分、有效 604800 秒，付款发券按订单中的快照生成权益。
     * 调用者负责新单创建及事务边界，后续模拟付款成功时发行用户券。
     *
     * @param orderId
     *                      本次刚插入的购买订单 ID
     * @param voucherId
     *                      该订单绑定的券模板/活动 ID
     * @see com.voucherx.order.service.PurchaseOrderCreationService#createPurchaseOrder
     * @see com.voucherx.commerce.mapper.PurchaseOrderMapper#initialize
     */
    public void initializePurchase(long orderId, long voucherId) {
        var terms = purchases.terms(voucherId);
        if (terms == null)
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        if (terms.payValue() < 0 || terms.actualValue() <= 0 || terms.minSpend() < 0
                || terms.validitySeconds() < 1 || terms.validitySeconds() > 315360000L)
            throw new BusinessException(ErrorCode.INVALID_PARAM, "优惠券金额或有效期配置不合法");
        purchases.initialize(orderId, terms, clock.millis() + config.paymentTimeoutMillis());
    }

    /**
     * 在同一事务中创建待付消费单、锁定用户券并保存 LOCK 动作。
     * <ol>
     * <li>普通读取用户券，按捕获的 now 检查归属、商户、有效期、状态和门槛，计算优惠。</li>
     * <li>生成 lockToken，插入消费单并取得数据库生成的订单 ID。</li>
     * <li>FOR UPDATE 锁券，使用相同 now 复核，以 AVAILABLE 和 version 条件绑定订单 ID 与令牌。</li>
     * <li>保存 LOCK 动作，全部写入共同提交或回滚。</li>
     * </ol>
     * <p>
     * 消费原额 10000 分、面额 2000 分时应付 8000 分；订单截止取付款超时和券失效时刻的较早者。
     * 锁券建立本次消费归属，后续成功付款将该券核销为 USED。
     *
     * @param userId
     *                           当前登录用户 ID
     * @param shopId
     *                           消费商户 ID
     * @param couponId
     *                           用户券 ID，不是活动 ID
     * @param originalAmount
     *                           原消费金额，分，当前简化模型由请求传入
     * @return 新消费订单 ID；最终提交取决于当前最外层事务边界
     * @see CommerceRules#validateAndCalculateDiscount
     * @see com.voucherx.commerce.mapper.ConsumptionOrderMapper#insert
     * @see com.voucherx.commerce.mapper.UserCouponMapper#markLockedForOrder
     */
    @Transactional
    public long createConsumptionOrder(long userId, long shopId, long couponId, long originalAmount) {
        // 所有路径按订单 -> 券加锁；新订单插入和锁券失败一起回滚。
        var couponBeforeLock = getCoupon(couponId);
        String lockToken = UUID.randomUUID().toString();
        long now = clock.millis();
        long discount = validateAndCalculateDiscount(couponBeforeLock, userId, shopId, originalAmount, now);
        var consumptionOrder = new ConsumptionOrder(0, userId, shopId, couponId, lockToken, originalAmount, discount,
                originalAmount - discount,
                "PENDING_PAYMENT",
                Math.min(now + config.paymentTimeoutMillis(), couponBeforeLock.validUntilMs()), now);
        var generatedOrderId = new GeneratedId();
        consumptions.insert(consumptionOrder, generatedOrderId);
        if (generatedOrderId.getId() == null)
            throw new IllegalStateException("数据库未返回主键");
        long consumptionOrderId = generatedOrderId.getId();
        var lockedCoupon = getCouponForUpdate(couponId);
        validateAndCalculateDiscount(lockedCoupon, userId, shopId, originalAmount, now);
        int changed = userCoupons.markLockedForOrder(couponId, consumptionOrderId, lockToken, lockedCoupon.version());
        if (changed != 1)
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券已被其他订单锁定");
        recordCouponAction(couponId, consumptionOrderId, lockToken, "LOCK", now);
        faults.hit(com.voucherx.recovery.FaultPoint.COUPON_LOCKED, String.valueOf(consumptionOrderId), lockToken);
        return consumptionOrderId;
    }

    /**
     * 将用户券快照交给纯规则函数，检查归属、商户、时间、状态及门槛并计算优惠。
     *
     * @param coupon
     *                           用户券快照
     * @param userId
     *                           当前用户 ID
     * @param shopId
     *                           消费商户 ID
     * @param originalAmount
     *                           消费原价，分
     * @param nowMs
     *                           本次操作时刻，Unix 毫秒
     * @return 不超过原价的实际优惠额，分
     */
    private long validateAndCalculateDiscount(UserCoupon coupon, long userId, long shopId, long originalAmount,
            long nowMs) {
        return CommerceRules.validateAndCalculateDiscount(coupon.userId(), userId, coupon.shopId(), shopId,
                coupon.validFromMs(),
                coupon.validUntilMs(), nowMs, coupon.state(), coupon.minSpend(), coupon.faceValue(), originalAmount);
    }

    /**
     * 在订单锁保护下核对并处理模拟支付事件。
     * <p>
     * 先校验开关、输入、订单归属与金额。同 eventId 已有记录时核对类型、订单、用户、金额和 success，匹配则返回首次回执，冲突则抛错。
     * 订单行锁协调付款与关闭，全局事件主键约束跨订单的 eventId 唯一性。
     * <ul>
     * <li>APPLIED：购买单发行用户券，消费单核销券，再条件写 PAID。</li>
     * <li>CONFLICT_EXPIRED：关闭到期的待付单，消费单同时释放自己的券锁。</li>
     * <li>其他分类：保存支付事件回执并保留相应业务状态。</li>
     * </ul>
     * <p>
     * 权益、订单状态与事件共同提交或回滚。success=false 返回 FAILED_ATTEMPT；已付款订单收到新的成功事件返回
     * ALREADY_PAID，已取消订单收到成功事件返回 CONFLICT_TERMINAL。
     *
     * @param userId
     *                          当前登录用户 ID
     * @param orderKind
     *                          非空订单类型 PURCHASE 或 CONSUMPTION
     * @param orderId
     *                          该类型中的订单 ID
     * @param eventId
     *                          全局支付事件幂等键，1 到 64 个允许字符
     * @param paymentAmount
     *                          声明支付金额，分，必须等于订单应付
     * @param success
     *                          本次模拟事件的支付成功声明
     * @return 本事件处理结果；重放时为首次处理回执，提交受最外层事务边界决定
     * @see CommerceRules#paymentOutcome
     * @see #issue
     * @see #redeem
     * @see #closeLocked
     */
    @Transactional
    public PaymentReceipt simulatePayment(long userId, OrderKind orderKind, long orderId, String eventId,
            long paymentAmount, boolean success) {
        if (!config.simulationEnabled())
            throw new BusinessException(ErrorCode.NOT_FOUND, "模拟支付未启用");
        if (eventId == null || !eventId.matches("[A-Za-z0-9._:-]{1,64}") || paymentAmount < 0)
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        var order = getOrderForUpdate(orderKind, orderId, userId);
        if (paymentAmount != order.payAmount())
            throw new BusinessException(ErrorCode.CONFLICT, "付款金额与订单不一致");
        var existingPaymentEvent = payments.selectById(eventId);
        if (existingPaymentEvent != null) {
            if (!orderKind.name().equals(existingPaymentEvent.orderKind()) || existingPaymentEvent.orderId() != orderId
                    || existingPaymentEvent.userId() != userId
                    || existingPaymentEvent.amount() != paymentAmount || existingPaymentEvent.success() != success)
                throw new BusinessException(ErrorCode.CONFLICT, "同一支付事件内容冲突");
            return new PaymentReceipt(eventId, existingPaymentEvent.outcome(), existingPaymentEvent.orderState());
        }
        long nowMs = clock.millis();
        String paymentStatus = order.paymentStatus();
        String outcome = CommerceRules.paymentOutcome(paymentStatus, order.paymentDeadlineMs(), nowMs, success);
        if ("APPLIED".equals(outcome)) {
            if (orderKind == OrderKind.PURCHASE)
                issue((PurchaseOrderSnapshot) order, nowMs);
            else
                redeem((ConsumptionOrder) order, nowMs);
            if ((orderKind == OrderKind.PURCHASE ? purchases.markPaid(orderId) : consumptions.markPaid(orderId)) != 1)
                throw new BusinessException(ErrorCode.CONFLICT);
            paymentStatus = "PAID";
        } else if ("CONFLICT_EXPIRED".equals(outcome)) {
            closeLocked(orderKind, order, nowMs);
            paymentStatus = "CANCELLED";
        }
        payments.insert(new PaymentEvent(eventId, orderKind.name(), orderId, userId, paymentAmount, success, outcome,
                paymentStatus, nowMs));
        faults.hit(com.voucherx.recovery.FaultPoint.PAYMENT_BEFORE_COMMIT, String.valueOf(orderId), eventId);
        return new PaymentReceipt(eventId, outcome, paymentStatus);
    }

    /**
     * 按购买订单的权益快照发行 AVAILABLE 用户券，validFrom 使用当前付款处理时刻。
     * <p>
     * 有效秒数乘 1000 再加当前时刻得到 validUntil，使用精确整数运算检查溢出。
     * 来源购买单唯一约束与订单行锁共同支持发行幂等；发券、PAID 和支付事件处于同一 simulatePayment 事务。
     *
     * @param order
     *                  已持锁、允许应用付款的购买单快照
     * @param nowMs
     *                  本次付款处理 Unix 毫秒
     * @see com.voucherx.commerce.mapper.UserCouponMapper#issue
     */
    private void issue(PurchaseOrderSnapshot order, long nowMs) {
        userCoupons.issue(order, nowMs, Math.addExact(nowMs, Math.multiplyExact(order.couponValiditySeconds(), 1000)));
    }

    /**
     * 在消费单锁保护下锁定用户券，核对 LOCKED 状态、用户、订单 ID、令牌及有效期。
     * <p>
     * 通过后将券记为 USED、增加 version 并写 USE 动作；付款状态与支付事件由外层 simulatePayment 一起提交。
     * 券的锁归属字段保留本次使用信息，行锁覆盖检查到更新的全过程。
     *
     * @param order
     *                  已持行锁的待付消费单
     * @param nowMs
     *                  本次付款处理 Unix 毫秒
     * @see #requireCouponOrderBinding
     * @see com.voucherx.commerce.mapper.UserCouponMapper#markUsed
     */
    private void redeem(ConsumptionOrder order, long nowMs) {
        long couponId = order.couponId();
        var lockedCoupon = getCouponForUpdate(couponId);
        requireCouponOrderBinding(order, lockedCoupon);
        if (nowMs < lockedCoupon.validFromMs() || nowMs >= lockedCoupon.validUntilMs())
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券已过期");
        userCoupons.markUsed(couponId);
        recordCouponAction(couponId, order.id(), order.lockToken(), "USE", nowMs);
    }

    /**
     * 持有订单行锁后处理当前用户的取消请求。
     * <p>
     * CANCELLED 直接返回，PENDING_PAYMENT 进入关闭流程，其他状态拒绝取消。
     * 消费单关闭时释放其所属券锁；购买单关闭时记录购买终态，发行占用保留。
     * <p>
     * 例如消费单 5001 已取消、券被 5002/L2 锁定，再次取消 5001 直接返回，因此 5002 的锁保持有效。
     * 付款与取消通过同一订单行锁串行决定终态。
     *
     * @param userId
     *                      当前登录用户 ID
     * @param orderKind
     *                      非空订单类型
     * @param orderId
     *                      对应类型的订单 ID
     * @return 正常关闭或幂等重放的 CANCELLED
     * @see #closeLocked
     */
    @Transactional
    public String cancel(long userId, OrderKind orderKind, long orderId) {
        var order = getOrderForUpdate(orderKind, orderId, userId);
        String state = order.paymentStatus();
        if ("CANCELLED".equals(state))
            return state;
        if (!"PENDING_PAYMENT".equals(state))
            throw new BusinessException(ErrorCode.CONFLICT, "当前订单不允许取消");
        closeLocked(orderKind, order, clock.millis());
        return "CANCELLED";
    }

    /**
     * 供到期任务逐笔调用的事务入口，先锁订单，再用当前业务时间复核仍待付且 now 已到付款截止。
     * <p>
     * 扫描只是候选，此处复核可避免把已经被付款提交的订单关掉；已不存在或不满足条件就返回。
     * 这条内部任务路径不依赖 HTTP 用户上下文，和主动取消共用 closeLocked。
     *
     * @param orderKind
     *                      候选订单的非空类型
     * @param orderId
     *                      候选订单 ID
     * @see CommerceExpiryJob#tick()
     */
    @Transactional
    public void expire(OrderKind orderKind, long orderId) {
        var order = selectOrderByIdForUpdate(orderKind, orderId);
        if (order == null)
            return;
        long now = clock.millis();
        if ("PENDING_PAYMENT".equals(order.paymentStatus()) && now >= order.paymentDeadlineMs())
            closeLocked(orderKind, order, now);
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
     * @param orderKind
     *                      订单类型
     * @param order
     *                      已加锁且调用者确认允许关闭的订单
     * @param nowMs
     *                      本次关闭判断使用的 Unix 毫秒
     * @see #requireCouponOrderBinding
     * @see CommerceRules#releasedState
     */
    private void closeLocked(OrderKind orderKind, CommerceOrder order, long nowMs) {
        if (orderKind == OrderKind.CONSUMPTION) {
            var consumption = (ConsumptionOrder) order;
            var lockedCoupon = getCouponForUpdate(consumption.couponId());
            requireCouponOrderBinding(consumption, lockedCoupon);
            userCoupons.clearOrderBindingAndSetState(lockedCoupon.id(),
                    CommerceRules.releasedState(lockedCoupon.validUntilMs(), nowMs));
            recordCouponAction(lockedCoupon.id(), order.id(), consumption.lockToken(), "RELEASE", nowMs);
        }
        if (orderKind == OrderKind.PURCHASE)
            purchases.cancel(order.id());
        else
            consumptions.cancel(order.id());
    }

    /**
     * 核对券的 LOCKED 状态及用户、lockOrderId、lockToken 三项消费归属。
     * <p>
     * 调用者已持订单和券行锁，保持至后续更新和事务提交。消费单 5001/L1 须与券上的归属完整匹配。
     * lockToken 为每次消费下单生成的归属令牌，version 用于条件更新。
     *
     * @param order
     *                   当前已锁定的消费单
     * @param coupon
     *                   当前已锁定的用户券快照
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             任一锁归属不匹配
     */
    private void requireCouponOrderBinding(ConsumptionOrder order, UserCoupon coupon) {
        if (!"LOCKED".equals(coupon.state()) || coupon.lockOrderId() == null
                || coupon.lockOrderId() != order.id() || !order.lockToken().equals(coupon.lockToken())
                || coupon.userId() != order.userId())
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券锁归属不匹配");
    }

    /**
     * 保存 LOCK、USE 或 RELEASE 动作及其用户券、消费单和归属令牌，与对应交易共同提交。
     *
     * @param couponId
     *                      用户券 ID
     * @param orderId
     *                      消费订单 ID
     * @param lockToken
     *                      本轮券锁归属令牌
     * @param action
     *                      LOCK、USE 或 RELEASE
     * @param nowMs
     *                      动作 Unix 毫秒
     */
    private void recordCouponAction(long couponId, long orderId, String lockToken, String action, long nowMs) {
        actions.insert(couponId, orderId, lockToken, action, nowMs);
    }

    /**
     * 普通查询指定类型订单并核对用户归属。
     *
     * @param orderKind
     *                      购买单或消费单类型
     * @param orderId
     *                      相应类型的订单 ID
     * @param userId
     *                      当前登录用户 ID
     * @return 当前用户拥有的订单快照
     */
    public CommerceOrder getOrder(OrderKind orderKind, long orderId, long userId) {
        return requireOwnedOrder(selectOrderById(orderKind, orderId), userId);
    }

    /**
     * 以 FOR UPDATE 取得订单行锁并核对用户归属；调用方事务须覆盖后续付款或关闭。
     *
     * @param orderKind
     *                      购买单或消费单类型
     * @param orderId
     *                      相应类型的订单 ID
     * @param userId
     *                      当前登录用户 ID
     * @return 当前用户拥有的已加锁订单
     */
    public CommerceOrder getOrderForUpdate(OrderKind orderKind, long orderId, long userId) {
        return requireOwnedOrder(selectOrderByIdForUpdate(orderKind, orderId), userId);
    }

    /** 核对已读取订单的存在性与用户归属。 */
    private CommerceOrder requireOwnedOrder(CommerceOrder order, long userId) {
        if (order == null || order.userId() != userId)
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        return order;
    }

    /** 按订单类型选择 Mapper 普通查询，返回值可以为空。 */
    private CommerceOrder selectOrderById(OrderKind orderKind, long orderId) {
        return orderKind == OrderKind.PURCHASE ? purchases.selectById(orderId) : consumptions.selectById(orderId);
    }

    /** 按订单类型选择 Mapper 的 FOR UPDATE 查询；行锁由调用方事务保持，返回值可以为空。 */
    private CommerceOrder selectOrderByIdForUpdate(OrderKind orderKind, long orderId) {
        return orderKind == OrderKind.PURCHASE ? purchases.selectByIdForUpdate(orderId)
                : consumptions.selectByIdForUpdate(orderId);
    }

    /**
     * 普通查询用户券并校验存在性，归属和使用规则由调用方验证。
     *
     * @param couponId
     *                     用户券 ID
     * @return 用户券快照
     */
    public UserCoupon getCoupon(long couponId) {
        return requireCoupon(userCoupons.selectById(couponId));
    }

    /**
     * 以 FOR UPDATE 取得用户券行锁并校验存在性；调用方按订单、券顺序加锁并保持事务。
     *
     * @param couponId
     *                     用户券 ID
     * @return 已加锁的用户券快照
     */
    public UserCoupon getCouponForUpdate(long couponId) {
        return requireCoupon(userCoupons.selectByIdForUpdate(couponId));
    }

    /** 统一检查用户券查询结果的存在性。 */
    private UserCoupon requireCoupon(UserCoupon coupon) {
        if (coupon == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在");
        return coupon;
    }

    /**
     * 按用户查询用户券，以券 ID 为游标升序分页。
     * <p>
     * after 是上一页末尾的券 ID；结果包含该用户各状态的券，供客户端展示及选择。
     *
     * @param userId
     *                          当前登录用户 ID
     * @param afterCouponId
     *                          上一页最后一张用户券 ID，首页为 0
     * @param pageSize
     *                          页大小，1 到 100
     * @return 用户自己的券列表；没有更多时为空列表
     */
    public List<UserCoupon> coupons(long userId, long afterCouponId, int pageSize) {
        if (afterCouponId < 0 || pageSize < 1 || pageSize > 100)
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        return userCoupons.page(userId, afterCouponId, pageSize);
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
    public List<Long> expiredOrderIds(OrderKind kind, long now, int limit) {
        return kind == OrderKind.PURCHASE ? purchases.expiredIds(now, limit) : consumptions.expiredIds(now, limit);
    }
}
