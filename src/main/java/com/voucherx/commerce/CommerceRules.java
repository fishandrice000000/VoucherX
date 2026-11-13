package com.voucherx.commerce;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;

/**
 * 交易纯规则函数：只计算及校验输入，不访问 Redis、数据库或外部支付服务。
 * <p>
 * 服务层在持有必要行锁后把快照传入，规则通过后仍须完成同一事务中的条件写入。
 */
public final class CommerceRules {
    private CommerceRules() {
    }

    /**
     * 验证当前用户拥有券、商户匹配、达到门槛、状态 AVAILABLE 且处于有效期内，再计算优惠。
     * <p>
     * 时间区间为包含起点、不包含终点。优惠取面额与原价的较小值，因此应付金额不会为负数。
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
     *                       券生效时刻，Unix 毫秒，包含
     * @param until
     *                       券失效时刻，Unix 毫秒，不包含
     * @param now
     *                       当前 Unix 毫秒
     * @param state
     *                       当前用户券状态
     * @param minSpend
     *                       使用门槛，分
     * @param face
     *                       券面额，分
     * @param original
     *                       消费原价，分
     *
     * @return 可抵扣金额，分
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
     * 券锁释放时判断是否已过期；即使到期扫描尚未执行，也按当前时间得出释放状态。
     *
     * @param until
     *                  券失效 Unix 毫秒
     * @param now
     *                  当前 Unix 毫秒
     *
     * @return 已到失效时刻返回 EXPIRED，否则 AVAILABLE
     */
    public static String releasedState(long until, long now) {
        return now >= until ? "EXPIRED" : "AVAILABLE";
    }

    /**
     * 按付款声明、已有订单状态和截止时间计算本事件结果。
     * <p>
     * 失败声明优先得到 FAILED_ATTEMPT，不触发关闭；成功声明才依次检查已付款、终态及超时。
     * 函数不写数据库，由服务层对 APPLIED 或 CONFLICT_EXPIRED 执行对应事务操作。
     *
     * @param state
     *                     订单当前付款状态
     * @param deadline
     *                     付款截止 Unix 毫秒
     * @param now
     *                     当前 Unix 毫秒
     * @param success
     *                     本次事件声明是否支付成功
     *
     * @return 事件处理分支，如 APPLIED、FAILED_ATTEMPT 或冲突原因
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
