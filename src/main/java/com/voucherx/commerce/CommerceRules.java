package com.voucherx.commerce;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;

/**
 * 04 的纯交易规则，输入已读到的快照和时间，输出优惠额、释放状态或支付事件处理分类。
 * <p>
 * 不访问存储，不持有行锁。消费初验可以无锁调用，最终写入前由服务层持锁复核；规则通过本身不表示交易已提交。
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
    public static long discount(long owner, long user, long couponShop, long shop, long from, long until,
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
     * 按固定优先级判定一个新支付事件，不读取旧事件记录也不直接修改订单。
     * <ol>
     * <li>success=false 始终为 FAILED_ATTEMPT，优先于已付、取消及超时检查，调用方保留原订单状态。</li>
     * <li>成功声明遇到 PAID 为 ALREADY_PAID，不再应用权益。</li>
     * <li>成功声明遇到非 PENDING_PAYMENT 为 CONFLICT_TERMINAL，LEGACY_CREATED 也属于此分支。</li>
     * <li>仍待付但 now 已到 deadline 为 CONFLICT_EXPIRED；否则 APPLIED。</li>
     * </ol>
     * <p>
     * 例如待付单截止 200：now=199 可应用，now=200 应关闭；已付款单即使超过原截止仍为 ALREADY_PAID。
     * 同 eventId 重放由 simulate 提前返回原回执，不会重新运行此规则。
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
     * @see CommerceService#simulate
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
