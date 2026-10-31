package com.voucherx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voucherx.shop-cache")
public record ShopCacheProperties(
        long ttlSeconds,
        long ttlRandomSeconds,
        long nullTtlSeconds,
        long lockTtlSeconds,
        long lockWaitMillis
) {
}
