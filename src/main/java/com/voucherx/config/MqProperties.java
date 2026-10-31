package com.voucherx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voucherx.mq")
public record MqProperties(
        int maxRetries,
        long retryTtlMillis
) {
}
