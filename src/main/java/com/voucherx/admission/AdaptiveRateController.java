package com.voucherx.admission;

/**
 * 纯 Java 的 AIMD 状态机：加性恢复、乘性下降，不直接访问 Redis 或执行定时任务。
 * <p>
 * 真正参与调速的信号是队列 ready、完成 TPS、已测得的连接池等待及基础设施失败比例。
 * 在途数用于样本有效性校验及 workPending/原因标签，不直接设置调速阈值；平均耗时、在途峰值、连接池活跃数当前只是观测值。
 * <p>
 * 过载可每窗口降速，冷却只限制上调。健康和无效反馈分别计数；少量无效反馈只保持，达到阈值才回退最小速率。
 * 状态由调用方串行驱动，本类没有同步保护，也不自动汇总多个实例的处理能力。
 *
 * @see AdaptiveAdmissionController
 * @see FeedbackSample
 */
public final class AdaptiveRateController {

    /**
     * 一次本地控制决定；changed 表示内存速率变化，不表示 Redis 发布已成功。
     *
     * @param rate
     *                     决定后的共享补充速率，令牌/秒
     * @param capacity
     *                     min(配置共享容量, max(1, rate)) 得到的突发容量
     * @param reason
     *                     OVERLOAD、FAILURE、HEALTHY、HOLD、IDLE_HOLD 或指标失效原因
     * @param changed
     *                     本次决定是否改变内存中的速率
     */
    public record Decision(double rate, double capacity, String reason, boolean changed) {
    }

    private final AdmissionProperties.Controller config;
    private final double sharedCapacity;
    /**
     * 当前内存中的共享允许速率，构造时钳制到配置最小/最大范围。
     */
    private double currentRate;
    /**
     * 恢复加速允许开始的单调时钟截止刻度；每次过载重新设置，不阻止继续降速。
     */
    private long cooldownUntilNanos;
    /**
     * 累计健康窗口数；有效但不健康或过载时清零，少量无效反馈保持已有值。
     */
    private int healthyWindows;
    /**
     * 连续无效反馈次数；收到有效样本清零，达到 healthWindows 时采用最小速率。
     */
    private int invalidWindows;

    /**
     * 保存控制参数及共享容量上限，将初始速率限制在 minRate 与 maxRate 之间。
     *
     * @param config
     *                           已校验的 AIMD 参数
     * @param sharedCapacity
     *                           配置共享容量上限
     */
    public AdaptiveRateController(AdmissionProperties.Controller config, double sharedCapacity) {
        this.config = config;
        this.sharedCapacity = sharedCapacity;
        this.currentRate = clamp(config.initialRate());
    }

    /**
     * 读取状态机当前速率，不重新采样，也不保证 Redis 已写入这个值。
     *
     * @return 当前内存允许速率，令牌/秒
     */
    public double currentRate() {
        return currentRate;
    }

    /**
     * 以一次窗口反馈推进控制状态，先判定样本有效性，再决定降速、恢复或保持。
     * <ol>
     * <li>无效样本累计到 healthWindows 才切到 minRate 并清空健康计数；此前保持速率及已有健康计数。</li>
     * <li>有效样本中，ready 超高水位、队列非空且完成 TPS 低于当前速率乘 minCompletionRatio、
     * 已测连接池有等待，或基础设施失败占比至少 10% 且有失败，任一成立即过载。</li>
     * <li>过载采用 max(minRate, currentRate*beta)，清空健康计数并重设冷却；例如默认 100*0.8=80。</li>
     * <li>不过载且 ready 不高于低水位、确有新订单完成、冷却已结束时累计健康计数；达到阈值后
     * 采用 min(maxRate, currentRate+alpha)，例如 80+10=90。</li>
     * </ol>
     * <p>
     * 没有队列积压时，低完成 TPS 不单独触发降速；没有新订单完成的空闲窗口也不会推动速率升至上限。
     * 无效样本未达阈值时不会打断已有健康计数，不能把这一分支误读为“每次无效都重新累计健康窗口”。
     * 所有分支只返回内存决定，实际发布及续有效期交给调度器。
     *
     * @param sample
     *                     采集器给出的非空窗口反馈
     * @param nowNanos
     *                     当前单调时钟纳秒刻度，用于冷却判断，不是 Unix 时间
     * @return 新速率、容量、原因和速率变化标志
     * @see AdmissionPolicyStore#updateShared
     */
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
        // 只有队列中确实有积压时才把“完成 TPS 低于当前允许速率”解释为能力不足。
        // 空闲或请求量本身低于允许速率时，在途数可能仍为 1，不能据此降速。
        boolean completionLag = sample.queueReady() > 0
                && sample.completedTps() < currentRate * config.minCompletionRatio();
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

    /**
     * 验证本控制器使用的最低反馈条件：窗口为正、队列成功测得且非负、在途和完成数非负、完成 TPS 有限。
     * <p>
     * 这不是所有字段的完整校验；不要求 Hikari 必须测得，也没有校验平均耗时、重复/失败计数等全部观测字段。
     *
     * @param sample
     *                   非空反馈样本
     * @return 满足当前最低条件时为 true
     */
    private boolean sampleValid(FeedbackSample sample) {
        return sample.windowMillis() > 0
                && sample.queueMeasured()
                && sample.queueReady() >= 0
                && sample.inFlight() >= 0
                && sample.completedOrders() >= 0
                && Double.isFinite(sample.completedTps());
    }

    /**
     * 根据当前速率计算突发容量并构造决定，不重置令牌，也不写共享策略。
     *
     * @param reason
     *                    分支原因
     * @param changed
     *                    本次是否改变内存速率
     * @return 包含当前速率及限幅容量的决定
     */
    private Decision decision(String reason, boolean changed) {
        double capacity = Math.min(sharedCapacity, Math.max(1.0, currentRate));
        return new Decision(currentRate, capacity, reason, changed);
    }

    /**
     * 将给定初始速率限制到配置的最小和最大边界。
     *
     * @param value
     *                  待约束的速率
     * @return 不低于 minRate 且不高于 maxRate 的速率
     */
    private double clamp(double value) {
        return Math.max(config.minRate(), Math.min(config.maxRate(), value));
    }
}
