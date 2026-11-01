package com.voucherx.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 方向 02 准入参数。默认 OFF；只有显式配置才启用本地/全局限流。
 *
 * 速率单位为请求/秒，容量单位为令牌数。所有策略都在库存预扣之前执行。
 */
@ConfigurationProperties(prefix = "voucherx.admission")
public record AdmissionProperties(
        AdmissionMode mode,
        Local local,
        Global global,
        Controller controller
) {

    public AdmissionProperties {
        if (mode == null) {
            mode = AdmissionMode.OFF;
        }
        if (local == null) {
            local = new Local(true, 1000, 100);
        }
        if (global == null) {
            global = new Global(true, 200, 200, 200, 200, 0);
        }
        if (controller == null) {
            controller = new Controller(2000, 100, 10, 400, 10, 0.8,
                    1000, 100, 0.9, 3, 10000, 10000);
        }
        validate(local, global, controller);
    }

    private static void validate(Local local, Global global, Controller controller) {
        if (!Double.isFinite(local.ratePerSecond()) || local.ratePerSecond() < 0
                || !Double.isFinite(local.capacity()) || local.capacity() <= 0) {
            throw new IllegalArgumentException("local admission rate/capacity invalid");
        }
        if (!Double.isFinite(global.sharedRate()) || global.sharedRate() < 0
                || !Double.isFinite(global.sharedCapacity()) || global.sharedCapacity() <= 0
                || !Double.isFinite(global.activityRate()) || global.activityRate() < 0
                || !Double.isFinite(global.activityCapacity()) || global.activityCapacity() <= 0
                || global.policyTtlMillis() < 0) {
            throw new IllegalArgumentException("global admission rate/capacity invalid");
        }
        if (controller.intervalMillis() <= 0
                || !Double.isFinite(controller.initialRate())
                || !Double.isFinite(controller.minRate()) || controller.minRate() <= 0
                || !Double.isFinite(controller.maxRate())
                || controller.maxRate() < controller.minRate()
                || controller.initialRate() < controller.minRate()
                || controller.initialRate() > controller.maxRate()
                || !Double.isFinite(controller.alpha()) || controller.alpha() <= 0
                || !Double.isFinite(controller.beta())
                || controller.beta() <= 0 || controller.beta() >= 1
                || controller.highWatermark() < controller.lowWatermark()
                || controller.lowWatermark() < 0
                || !Double.isFinite(controller.minCompletionRatio())
                || controller.minCompletionRatio() < 0 || controller.minCompletionRatio() > 1
                || controller.healthWindows() <= 0
                || controller.cooldownMillis() < 0
                || controller.policyTtlMillis() <= 0) {
            throw new IllegalArgumentException("adaptive admission controller config invalid");
        }
    }

    public record Local(
            boolean enabled,
            double ratePerSecond,
            double capacity
    ) {
    }

    public record Global(
            boolean enabled,
            double sharedRate,
            double sharedCapacity,
            double activityRate,
            double activityCapacity,
            long policyTtlMillis
    ) {
    }

    public record Controller(
            long intervalMillis,
            double initialRate,
            double minRate,
            double maxRate,
            double alpha,
            double beta,
            long highWatermark,
            long lowWatermark,
            double minCompletionRatio,
            int healthWindows,
            long cooldownMillis,
            long policyTtlMillis
    ) {
    }
}
