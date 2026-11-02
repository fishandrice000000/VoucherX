package com.voucherx.reconcile;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("voucherx.reconcile")
public record ReconcileProperties(@DefaultValue("false") boolean enabled,@DefaultValue("100") int scanSize,
        @DefaultValue("30000") long staleMillis,@DefaultValue("5000") long retryMillis,@DefaultValue("8") int maxAttempts) {
    public ReconcileProperties {
        if(scanSize<1 || scanSize>1000 || staleMillis<1000 || retryMillis<100 || maxAttempts<1 || maxAttempts>100)
            throw new IllegalArgumentException("无效对账预算");
    }
}
