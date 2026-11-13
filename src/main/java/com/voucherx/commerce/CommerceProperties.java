package com.voucherx.commerce;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 交易配置，绑定 voucherx.commerce；模拟支付入口默认关闭，超时和扫描仍为独立业务配置。
 *
 * @param simulationEnabled
 *                                 是否开放模拟支付事件处理，默认 false
 * @param paymentTimeoutMillis
 *                                 待付超时毫秒，默认 900000；消费单还取与券失效时刻较早者
 * @param scanSize
 *                                 默认 100；每类订单候选和 AVAILABLE 券更新分别使用这个上限，不是整轮合计上限
 */
@ConfigurationProperties("voucherx.commerce")
public record CommerceProperties(@DefaultValue("false") boolean simulationEnabled,
        @DefaultValue("900000") long paymentTimeoutMillis, @DefaultValue("100") int scanSize) {
    /**
     * 约束付款超时范围与扫描规模，启动时拒绝不合法配置。
     *
     * @param simulationEnabled
     *                                 是否开启模拟支付
     * @param paymentTimeoutMillis
     *                                 付款超时毫秒
     * @param scanSize
     *                                 扫描上限
     * @throws IllegalArgumentException
     *                                      超时不在 1 秒到 1 天，或扫描条数不在 1 到 1000
     */
    public CommerceProperties {
        if (paymentTimeoutMillis < 1000 || paymentTimeoutMillis > 86400000 || scanSize < 1 || scanSize > 1000)
            throw new IllegalArgumentException("无效的支付超时/扫描配置");
    }
}
