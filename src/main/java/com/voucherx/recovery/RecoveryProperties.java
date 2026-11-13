package com.voucherx.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("voucherx.recovery")
public record RecoveryProperties(@DefaultValue("200") int pageSize, @DefaultValue("100000") int maxRecords) {
    public RecoveryProperties {
        if (pageSize < 1 || pageSize > 1000 || maxRecords < pageSize || maxRecords > 1000000)
            throw new IllegalArgumentException("无效恢复分页/记录预算");
    }
}
