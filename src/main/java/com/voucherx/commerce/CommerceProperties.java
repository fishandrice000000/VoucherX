package com.voucherx.commerce;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("voucherx.commerce")
public record CommerceProperties(@DefaultValue("false") boolean simulationEnabled,
        @DefaultValue("900000") long paymentTimeoutMillis,@DefaultValue("100") int scanSize) {
    public CommerceProperties {
        if(paymentTimeoutMillis<1000 || paymentTimeoutMillis>86400000 || scanSize<1 || scanSize>1000)
            throw new IllegalArgumentException("无效的支付超时/扫描配置");
    }
}
