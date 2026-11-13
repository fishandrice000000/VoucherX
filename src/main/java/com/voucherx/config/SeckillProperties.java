package com.voucherx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voucherx.seckill")
public record SeckillProperties(
        long publishConfirmTimeoutMillis,
        long requestStatusTtlSeconds,
        int bucketAttempts) {
}
