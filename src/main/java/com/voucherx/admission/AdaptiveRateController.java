package com.voucherx.admission;

/**
 * 纯 AIMD 状态机。把控制逻辑与调度、Redis 发布分开，便于用确定性样本测试。
 *
 * 当前启用的反馈信号：主队列 ready、消费者在途、实际新订单完成 TPS、事务耗时、
 * 失败率和 Hikari 等待。老队列年龄未可靠获得，因此不作为已实现信号。
 */
public final class AdaptiveRateController {

    public record Decision(double rate, double capacity, String reason, boolean changed) {
    }

    private final AdmissionProperties.Controller config;
    private final double sharedCapacity;
    private double currentRate;
    private long cooldownUntilNanos;
    private int healthyWindows;
    private int invalidWindows;

    public AdaptiveRateController(AdmissionProperties.Controller config, double sharedCapacity) {
        this.config = config;
        this.sharedCapacity = sharedCapacity;
        this.currentRate = clamp(config.initialRate());
    }

    public double currentRate() {
        return currentRate;
    }

    public Decision evaluate(FeedbackSample sample, long nowNanos) {
        if (!sampleValid(sample)) {
            invalidWindows++;
            if (invalidWindows >= Math.max(1, config.healthWindows())) {
                double conservative = config.minRate();
                boolean changed = conservative != currentRate;
                currentRate = conservative;
                healthyWindows = 0;
                return decision("METRICS_INVALID", changed);
            }
            return decision("METRICS_INVALID_HOLD", false);
        }
        invalidWindows = 0;

        boolean pendingWork = sample.workPending();
        boolean completionLag = pendingWork && sample.completedTps()
                < currentRate * config.minCompletionRatio();
        boolean overloaded = sample.queueReady() > config.highWatermark()
                || completionLag
                || (sample.hikariMeasured() && sample.hikariPending() > 0)
                || (sample.failureRatio() >= 0.10 && sample.failures() > 0);

        if (overloaded) {
            healthyWindows = 0;
            double next = Math.max(config.minRate(), currentRate * config.beta());
            boolean changed = next != currentRate;
            currentRate = next;
            cooldownUntilNanos = nowNanos + config.cooldownMillis() * 1_000_000L;
            return decision(pendingWork ? "OVERLOAD" : "FAILURE", changed);
        }

        boolean healthy = sample.queueReady() <= config.lowWatermark()
                && sample.completedOrders() > 0
                && nowNanos >= cooldownUntilNanos;
        if (healthy) {
            healthyWindows++;
        } else {
            healthyWindows = 0;
        }
        if (healthy && healthyWindows >= Math.max(1, config.healthWindows())) {
            healthyWindows = 0;
            double next = Math.min(config.maxRate(), currentRate + config.alpha());
            boolean changed = next != currentRate;
            currentRate = next;
            return decision("HEALTHY", changed);
        }
        return decision(pendingWork ? "HOLD" : "IDLE_HOLD", false);
    }

    private boolean sampleValid(FeedbackSample sample) {
        return sample.windowMillis() > 0
                && sample.queueMeasured()
                && sample.queueReady() >= 0
                && sample.inFlight() >= 0
                && sample.completedOrders() >= 0
                && Double.isFinite(sample.completedTps());
    }

    private Decision decision(String reason, boolean changed) {
        double capacity = Math.min(sharedCapacity, Math.max(1.0, currentRate));
        return new Decision(currentRate, capacity, reason, changed);
    }

    private double clamp(double value) {
        return Math.max(config.minRate(), Math.min(config.maxRate(), value));
    }
}
