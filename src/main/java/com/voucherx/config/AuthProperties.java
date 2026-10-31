package com.voucherx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "voucherx.auth")
public record AuthProperties(
        long codeTtlSeconds,
        long codeCooldownSeconds,
        int ipLimitPerMinute,
        long tokenTtlSeconds,
        boolean exposeCode
) {

    public Duration codeTtl() {
        return Duration.ofSeconds(codeTtlSeconds);
    }

    public Duration tokenTtl() {
        return Duration.ofSeconds(tokenTtlSeconds);
    }
}
