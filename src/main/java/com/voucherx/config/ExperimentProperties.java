package com.voucherx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 仅用于实验环境的故障注入配置。默认关闭，生产请求不能修改这些参数。
 */
@ConfigurationProperties(prefix = "voucherx.experiment")
public record ExperimentProperties(
        boolean enabled,
        long transactionDelayMillis,
        String delayFile) {

    public ExperimentProperties {
        if (transactionDelayMillis < 0) {
            throw new IllegalArgumentException("experiment transaction delay must be non-negative");
        }
        if (delayFile == null || delayFile.isBlank()) {
            delayFile = "/tmp/voucherx-experiment-delay-ms";
        }
    }
}
