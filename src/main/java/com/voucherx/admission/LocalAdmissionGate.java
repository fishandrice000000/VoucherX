package com.voucherx.admission;

import org.springframework.stereotype.Component;

/**
 * 秒杀单实例本地突发保护。全局预算仍由 Redis 令牌桶约束。
 */
@Component
public class LocalAdmissionGate {

    private final AdmissionProperties properties;
    private final LocalTokenBucket bucket;

    public LocalAdmissionGate(AdmissionProperties properties) {
        this.properties = properties;
        AdmissionProperties.Local local = properties.local();
        this.bucket = local.enabled() && local.ratePerSecond() > 0
                ? new LocalTokenBucket(local.ratePerSecond(), local.capacity())
                : null;
    }

    public boolean tryAcquire() {
        return properties.mode() == AdmissionMode.OFF || bucket == null || bucket.tryAcquire();
    }
}
