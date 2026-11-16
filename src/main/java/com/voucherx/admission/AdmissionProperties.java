package com.voucherx.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 请求准入配置，绑定 voucherx.admission；速率单位为令牌/秒，容量单位为令牌数。
 * <p>
 * 顶层缺失配置时使用构造器默认值；application.yml 提供同名配置及环境变量入口，默认 mode=OFF。
 * 启动时校验各组参数。ADAPTIVE 动态发布共享策略，活动限额由 Global 提供。
 *
 * @param mode
 *                       OFF、FIXED 或 ADAPTIVE，缺失时 OFF
 * @param local
 *                       单实例突发保护参数
 * @param global
 *                       Redis 共享/活动桶参数
 * @param controller
 *                       AIMD 调度及调整参数
 * @see SeckillAdmissionService
 * @see AdaptiveAdmissionController
 */
@ConfigurationProperties(prefix = "voucherx.admission")
public record AdmissionProperties(
        AdmissionMode mode,
        Local local,
        Global global,
        Controller controller) {

    /**
     * 为缺失的组补默认值，然后统一验证；这一步不创建 Redis 策略或令牌键。
     *
     * @param mode
     *                       准入模式，null 时 OFF
     * @param local
     *                       本地配置，null 时 1000/秒、容量 100 且启用
     * @param global
     *                       全局配置，null 时共享与活动均 200/秒、容量 200 且启用
     * @param controller
     *                       控制参数，null 时采用构造器列出的默认值
     * @throws IllegalArgumentException
     *                                      任一限流或控制参数不满足约束
     */
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

    /**
     * 要求速率有限且非负、容量有限且为正；控制速率位于最小/最大界内，alpha 为正、beta 在 0 与 1 之间。
     * <p>
     * 另校验高低水位、完成比例、健康窗口、冷却和有限策略 TTL；即使 mode=OFF 也执行，避免开启时才发现配置错误。
     *
     * @param local
     *                       非空本地参数
     * @param global
     *                       非空全局参数
     * @param controller
     *                       非空控制参数
     * @throws IllegalArgumentException
     *                                      任一参数非法
     */
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

    /**
     * 单实例本地保护配置；每个应用实例单独持有一个桶。
     * <p>
     * LocalAdmissionGate 在禁用或 ratePerSecond=0 时不创建桶，因此配置 0 不表示禁止所有请求。
     *
     * @param enabled
     *                          是否创建本地保护桶
     * @param ratePerSecond
     *                          每秒补充令牌数，默认 1000
     * @param capacity
     *                          突发容量，默认 100
     */
    public record Local(
            boolean enabled,
            double ratePerSecond,
            double capacity) {
    }

    /**
     * Redis 双层预算配置：共享桶限制整体新请求，活动桶限制单活动。
     * <p>
     * 共享与活动桶先判断再共同扣减。固定策略缺失时由获取脚本按默认速率和容量创建，validUntil 为 0。
     * policyTtlMillis 当前只参与配置绑定和校验，没有传给获取脚本；ADAPTIVE 发布使用 Controller 的 TTL。
     *
     * @param enabled
     *                             是否执行全局请求准入；此开关本身不停止 ADAPTIVE 调度器
     * @param sharedRate
     *                             默认共享速率，令牌/秒
     * @param sharedCapacity
     *                             默认共享容量
     * @param activityRate
     *                             默认活动速率，令牌/秒
     * @param activityCapacity
     *                             默认活动容量
     * @param policyTtlMillis
     *                             当前仅绑定和校验的非负毫秒配置；不直接控制实际策略有效期
     */
    public record Global(
            boolean enabled,
            double sharedRate,
            double sharedCapacity,
            double activityRate,
            double activityCapacity,
            long policyTtlMillis) {
    }

    /**
     * 共享速率控制参数；定时任务读取同名 interval-millis 配置。
     * <p>
     * 项目默认初始速率 100，过载按 beta=0.8 降低，健康按 alpha=10 恢复，限制在 10 到 400。
     * healthWindows 同时作为健康累计和连续无效反馈阈值；冷却期间仍可以继续降低。
     *
     * @param intervalMillis
     *                               配置的采样调度固定延迟，毫秒，默认 2000
     * @param initialRate
     *                               初始共享速率，令牌/秒，默认 100
     * @param minRate
     *                               最小共享速率，默认 10
     * @param maxRate
     *                               最大共享速率，默认 400
     * @param alpha
     *                               每次健康恢复增加的速率，默认 10
     * @param beta
     *                               每次过载保留的速率比例，默认 0.8，严格介于 0 与 1
     * @param highWatermark
     *                               主队列 ready 高水位，超过时判过载，默认 1000
     * @param lowWatermark
     *                               健康累计要求 ready 不高于此值，默认 100
     * @param minCompletionRatio
     *                               队列非空时完成 TPS 相对当前速率的最低比例，默认 0.9
     * @param healthWindows
     *                               健康累计及连续无效反馈阈值，默认 3
     * @param cooldownMillis
     *                               过载后禁止上调的时长，毫秒，默认 10000
     * @param policyTtlMillis
     *                               每次控制器发布时的共享策略有效时长，毫秒，默认 10000，必须为正
     */
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
            long policyTtlMillis) {
    }
}
