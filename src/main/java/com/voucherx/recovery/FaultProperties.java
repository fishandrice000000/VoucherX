package com.voucherx.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("voucherx.fault")
public record FaultProperties(@DefaultValue("false") boolean enabled, @DefaultValue("") String point,
        @DefaultValue("*") String subject, @DefaultValue("FAIL") String action,
        @DefaultValue("manual") String runId, @DefaultValue("5000") long waitMillis,
        @DefaultValue("tmp/fault/evidence.jsonl") String evidencePath) {
    public FaultProperties {
        if (waitMillis < 1 || waitMillis > 30000)
            throw new IllegalArgumentException("故障等待上限为 30 秒");
    }
}
