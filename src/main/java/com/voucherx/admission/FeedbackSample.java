package com.voucherx.admission;

/**
 * 一个控制窗口的反馈样本。缺失的字段用 measured=false 表示，不能当作 0。
 */
public record FeedbackSample(
        long windowMillis,
        long completedOrders,
        long duplicateMessages,
        long failures,
        long businessFailures,
        double averageTransactionMillis,
        int inFlight,
        int maxInFlight,
        long queueReady,
        boolean queueMeasured,
        int hikariActive,
        int hikariPending,
        boolean hikariMeasured) {

    public double completedTps() {
        return windowMillis <= 0 ? 0 : completedOrders * 1000.0 / windowMillis;
    }

    public double failureRatio() {
        long outcomes = completedOrders + duplicateMessages + failures;
        return outcomes == 0 ? 0 : (double) failures / outcomes;
    }

    public boolean workPending() {
        return queueReady > 0 || inFlight > 0;
    }
}
