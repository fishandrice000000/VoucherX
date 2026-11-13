package com.voucherx.admission;

import org.springframework.stereotype.Component;

/**
 * 把准入模式与本地桶开关组合成单实例入口保护；不代表跨实例总预算。
 * <p>
 * 创建 gate 时只在 local.enabled=true 且速率大于 0 时创建满令牌桶。
 * 因此本 gate 配置速率 0 会跳过本地保护，不等同于“禁止所有请求”；全局 Redis 层独立判断。
 *
 * @see LocalTokenBucket
 * @see SeckillAdmissionService#acquireLocal()
 */
@Component
public class LocalAdmissionGate {

    private final AdmissionProperties properties;
    private final LocalTokenBucket bucket;

    /**
     * 从配置创建本实例令牌桶；关闭本地保护或速率为 0 时保留空引用，由 tryAcquire 直接放行。
     *
     * @param properties
     *                       已通过启动校验的准入配置
     */
    public LocalAdmissionGate(AdmissionProperties properties) {
        this.properties = properties;
        AdmissionProperties.Local local = properties.local();
        this.bucket = local.enabled() && local.ratePerSecond() > 0
                ? new LocalTokenBucket(local.ratePerSecond(), local.capacity())
                : null;
    }

    /**
     * OFF 或未创建本地桶时通过；否则调用同步令牌桶立即尝试一次，不访问 Redis/MySQL。
     *
     * @return 本地保护允许本次请求时为 true；令牌不足为 false
     */
    public boolean tryAcquire() {
        return properties.mode() == AdmissionMode.OFF || bucket == null || bucket.tryAcquire();
    }
}
