package com.voucherx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事务延迟实验配置，绑定 voucherx.experiment；application.yml 默认关闭，正常下单不注入额外等待。
 *
 * @param enabled
 *                                   是否启用实验延迟
 * @param transactionDelayMillis
 *                                   文件缺失或不可读时采用的初始延迟，毫秒；实际执行另限制为最多 10000
 * @param delayFile
 *                                   本地延迟控制文件路径；未配置时使用默认临时文件路径
 */
@ConfigurationProperties(prefix = "voucherx.experiment")
public record ExperimentProperties(
        boolean enabled,
        long transactionDelayMillis,
        String delayFile) {

    /**
     * 拒绝负的初始延迟，并为空路径填入默认控制文件；不创建或读取该文件。
     *
     * @param enabled
     *                                   实验开关
     * @param transactionDelayMillis
     *                                   初始等待毫秒数，必须非负
     * @param delayFile
     *                                   控制文件路径，空值或空白时采用默认值
     * @throws IllegalArgumentException
     *                                      初始事务延迟小于 0
     */
    public ExperimentProperties {
        if (transactionDelayMillis < 0) {
            throw new IllegalArgumentException("experiment transaction delay must be non-negative");
        }
        if (delayFile == null || delayFile.isBlank()) {
            delayFile = "/tmp/voucherx-experiment-delay-ms";
        }
    }
}
