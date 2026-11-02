package com.voucherx.fulfillment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("voucherx.delivery")
public record DeliveryProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("32") int scanSize, @DefaultValue("30000") long leaseMillis,
        @DefaultValue("8") int maxAttempts, @DefaultValue("1000") long retryMillis,
        @DefaultValue("false") boolean batchEnabled, @DefaultValue("16") int batchSize,
        @DefaultValue("1048576") int batchBytes, @DefaultValue("50") int batchWaitMillis) {
    public DeliveryProperties {
        if (scanSize < 1 || scanSize > 1000 || leaseMillis < 1000 || maxAttempts < 1 || retryMillis < 1
                || batchSize < 1 || batchSize > 100 || batchBytes < 1024 || batchWaitMillis < 1 || batchWaitMillis > 1000)
            throw new IllegalArgumentException("无效的投递/批量配置");
    }
    public long backoff(int attempt) { return Math.min(60000, retryMillis * (1L << Math.min(10, Math.max(0, attempt - 1)))); }
}
