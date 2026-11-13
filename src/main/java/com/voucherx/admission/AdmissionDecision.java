package com.voucherx.admission;

/**
 * 一次准入的分类结果；预算不足和策略不可用的 HTTP 语义不同，不能都解释为售罄。
 *
 * @see SeckillAdmissionService
 */
public enum AdmissionDecision {
    /**
     * Lua 返回 1：共享与活动预算都已扣减，可继续新意图和预留。
     */
    ALLOWED,
    /**
     * 本地突发拒绝的分类值；当前本地路径直接抛业务异常，Redis 脚本不返回此值。
     */
    BURST_REJECTED,
    /**
     * Lua 返回 -1：系统共享预算不足，对外 429。
     */
    SHARED_REJECTED,
    /**
     * Lua 返回 -2：当前活动预算不足，对外 429；不白扣共享令牌。
     */
    ACTIVITY_REJECTED,
    /**
     * Lua 返回 -3：要求的策略缺失、已过期或自适应策略没有有限有效期，对外 503。
     */
    POLICY_STALE,
    /**
     * 策略/返回值非法或脚本无返回，无法可信地判定准入，对外 503。
     */
    INVALID_POLICY
}
