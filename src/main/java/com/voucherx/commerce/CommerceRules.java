package com.voucherx.commerce;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;

/**
 * 根据交易快照、金额及时间计算优惠、券释放状态和支付事件分类。
 * <p>
 * 规则函数只计算结果，服务层负责读取快照、持锁复核及事务提交。
 *
 * @see CommerceService
 */
public final class CommerceRules {
    private CommerceRules() {
    }

    /**
     * 依次验证持有人、商户及金额门槛，再验证 AVAILABLE 和有效区间，最后返回 min(face, original)。
     * <p>
     * 区间包含 from、不包含 until；不要求定时任务先把到期券标成 EXPIRED 才拒绝使用。
     * 例如原额 10000 分、门槛 5000、面额 2000，优惠为 2000 分，应付由服务层算为 8000；面额超过原额时最多抵完原额。
     * 当前 original 来自简化消费输入，本函数不查询商品目录或确定商品真实售价。
     *
     * @param owner
     *                       券所属用户 ID
     * @param user
     *                       当前登录用户 ID
     * @param couponShop
     *                       券限定商户 ID
     * @param shop
     *                       实际消费商户 ID
     * @param from
     *                       生效 Unix 毫秒，包含该时刻
     * @param until
     *                       失效 Unix 毫秒，不包含该时刻
     * @param now
     *                       本次校验采用的 Unix 毫秒
     * @param state
     *                       当前用户券状态
     * @param minSpend
     *                       使用门槛，分
     * @param face
     *                       券面额，分
     * @param original
     *                       原消费金额，分
     * @return 实际优惠额，分，不超过原消费金额
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             归属、商户、金额门槛、状态或有效期不满足
     */
    public static long validateAndCalculateDiscount(long owner, long user, long couponShop, long shop, long from,
            long until,
            long now, String state, long minSpend, long face, long original) {
        if (owner != user)
            throw new BusinessException(ErrorCode.FORBIDDEN);
        if (original <= 0 || face <= 0 || minSpend < 0 || original < minSpend || couponShop != shop)
            throw new BusinessException(ErrorCode.INVALID_PARAM, "金额、门槛或商户不匹配");
        if (!"AVAILABLE".equals(state) || now < from || now >= until)
            throw new BusinessException(ErrorCode.CONFLICT, "优惠券当前不可用");
        return Math.min(face, original);
    }

    /**
     * 取消/到期关闭释放券锁时，以失效时刻判断释放后的状态；恰好等于 until 也应过期。
     *
     * @param until
     *                  券失效 Unix 毫秒
     * @param now
     *                  本次关闭 Unix 毫秒
     * @return now 已到 until 为 EXPIRED，否则 AVAILABLE
     */
    public static String releasedState(long until, long now) {
        return now >= until ? "EXPIRED" : "AVAILABLE";
    }

    /**
     * 按优先级分类一次支付声明。
     * <ol>
     * <li>success=false 返回 FAILED_ATTEMPT。</li>
     * <li>成功声明遇到 PAID 返回 ALREADY_PAID。</li>
     * <li>成功声明遇到其他非待付状态返回 CONFLICT_TERMINAL。</li>
     * <li>待付单达到截止时刻返回 CONFLICT_EXPIRED，仍在期限内返回 APPLIED。</li>
     * </ol>
     * <p>
     * 例如截止 200：now=199 可以应用，now=200 进入到期处理。同 eventId 的重复调用由 simulatePayment
     * 返回其首次处理回执。
     *
     * @param state
     *                     订单当前付款状态
     * @param deadline
     *                     付款截止 Unix 毫秒
     * @param now
     *                     本事件处理 Unix 毫秒
     * @param success
     *                     本事件声明是否支付成功
     * @return 处理分类；仅 APPLIED 应用权益，CONFLICT_EXPIRED 进入关闭
     * @see CommerceService#simulatePayment
     */
    public static String paymentOutcome(String state, long deadline, long now, boolean success) {
        if (!success)
            return "FAILED_ATTEMPT";
        if ("PAID".equals(state))
            return "ALREADY_PAID";
        if (!"PENDING_PAYMENT".equals(state))
            return "CONFLICT_TERMINAL";
        if (now >= deadline)
            return "CONFLICT_EXPIRED";
        return "APPLIED";
    }
}
