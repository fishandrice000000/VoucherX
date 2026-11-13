package com.voucherx.recovery;

/** 仅在冻结后，以同一数据库权威状态计算。核销/过期不减少 confirmed。 */
public record RecoveryPlan(long available, long reserved, long confirmed) {
    public static RecoveryPlan calculate(long total, long databaseRemaining, long confirmed, long reserved) {
        if (total < 0 || databaseRemaining < 0 || confirmed < 0 || reserved < 0 || databaseRemaining > total
                || confirmed > total || total - databaseRemaining != confirmed || reserved > databaseRemaining)
            throw new IllegalStateException("无法证明发行额度守恒，保持活动冻结");
        return new RecoveryPlan(databaseRemaining - reserved, reserved, confirmed);
    }
}
