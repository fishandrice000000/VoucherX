package com.voucherx.commerce;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 交易演示配置，绑定 voucherx.commerce；模拟支付默认关闭，避免把演示结果当成真实支付。
 *
 * @param simulationEnabled
 *                                 是否开放模拟支付接口
 * @param paymentTimeoutMillis
 *                                 待付超时时长，毫秒，默认 900000（15 分钟）
 * @param scanSize
 *                                 每轮扫描条数，默认 100
 */
@ConfigurationProperties("voucherx.commerce")
public record CommerceProperties(@DefaultValue("false") boolean simulationEnabled,
        @DefaultValue("900000") long paymentTimeoutMillis, @DefaultValue("100") int scanSize) {
    /**
     * 约束付款超时范围与扫描规模，启动时拒绝不合法配置。
     *
     * @throws IllegalArgumentException
     *                                      超时不在 1 秒到 1 天，或扫描条数不在 1 到 1000
     *
     * @param simulationEnabled
     *                                 是否开启模拟支付
     * @param paymentTimeoutMillis
     *                                 付款超时毫秒
     * @param scanSize
     *                                 扫描上限
     */
    public CommerceProperties {
        if (paymentTimeoutMillis < 1000 || paymentTimeoutMillis > 86400000 || scanSize < 1 || scanSize > 1000)
            throw new IllegalArgumentException("无效的支付超时/扫描配置");
    }
}
