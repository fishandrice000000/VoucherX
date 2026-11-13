package com.voucherx.reconcile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 05 的自动检查及发行预留补偿预算，绑定 voucherx.reconcile。
 * <p>
 * 观察窗口和重试等待都以毫秒计；陈旧只触发查证，不构成失败事实。
 * 这里的 maxAttempts 用于补偿执行，与 Outbox 的 DeliveryProperties.maxAttempts 是两套进度。
 *
 * @param enabled
 *                        是否自动 tick 扫描，默认 false；不限制显式 scan 或维护调用
 * @param scanSize
 *                        请求候选和补偿候选各自最多条数，默认 100
 * @param staleMillis
 *                        请求距最后业务更新的观察窗口，默认 30000
 * @param retryMillis
 *                        请求下次检查及补偿失败后等待，默认 5000
 * @param maxAttempts
 *                        补偿正常记账的尝试预算，默认 8；异常回滚的尝试不会被这次数据库计数保留
 */
@ConfigurationProperties("voucherx.reconcile")
public record ReconcileProperties(@DefaultValue("false") boolean enabled, @DefaultValue("100") int scanSize,
        @DefaultValue("30000") long staleMillis, @DefaultValue("5000") long retryMillis,
        @DefaultValue("8") int maxAttempts) {
    /**
     * 验证观察窗口、扫描规模和补偿预算；不在构造时启动扫描或创建补偿记录。
     *
     * @param enabled
     *                        自动扫描开关
     * @param scanSize
     *                        每类候选上限，1 到 1000
     * @param staleMillis
     *                        至少 1000 毫秒
     * @param retryMillis
     *                        至少 100 毫秒
     * @param maxAttempts
     *                        补偿记账尝试预算，1 到 100
     * @throws IllegalArgumentException
     *                                      参数不在构造器规定范围
     */
    public ReconcileProperties {
        if (scanSize < 1 || scanSize > 1000 || staleMillis < 1000 || retryMillis < 100 || maxAttempts < 1
                || maxAttempts > 100)
            throw new IllegalArgumentException("无效对账预算");
    }
}
