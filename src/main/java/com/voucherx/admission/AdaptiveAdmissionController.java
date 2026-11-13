package com.voucherx.admission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 反馈侧调度器：采集窗口 → 推进 AIMD → 发布带有限有效期的 Redis 共享策略。
 * <p>
 * 仅在 ADAPTIVE 模式初始化和运行；maintenance-mode 下此 Bean 不创建。默认模式 OFF，因此默认不执行控制闭环。
 * 即使速率未变，每轮也发布以续 validUntil；停更或发布持续失败后，过期策略使新准入返回 503。
 * <p>
 * 每个应用实例有自己的状态机及本地消费者反馈；当前没有主节点选举或跨实例完成量聚合。
 * 活动桶配额、消费者线程数和库存均不在这个控制器中动态调整。
 *
 * @see AdmissionFeedbackCollector#collect()
 * @see AdaptiveRateController#evaluate
 * @see AdmissionPolicyStore#updateShared
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "voucherx.maintenance-mode", havingValue = "false", matchIfMissing = true)
public class AdaptiveAdmissionController {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveAdmissionController.class);

    private final AdmissionProperties properties;
    private final AdmissionFeedbackCollector collector;
    private final AdmissionPolicyStore policyStore;
    private final AdaptiveRateController controller;

    public AdaptiveAdmissionController(AdmissionProperties properties,
            AdmissionFeedbackCollector collector,
            AdmissionPolicyStore policyStore) {
        this.properties = properties;
        this.collector = collector;
        this.policyStore = policyStore;
        this.controller = new AdaptiveRateController(properties.controller(),
                properties.global().sharedCapacity());
    }

    /**
     * 应用就绪后，在 ADAPTIVE 模式发布初始速率和有效期；其他模式直接返回，不等待先出现反馈。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (properties.mode() != AdmissionMode.ADAPTIVE) {
            return;
        }
        publish("INITIAL");
    }

    /**
     * 在 ADAPTIVE 模式按配置固定延迟采样一次，用 nanoTime 推进冷却状态，再发布当前速率。
     * <p>
     * 速率变化或非普通保持分支才记录调整日志；HOLD/IDLE_HOLD 仍会发布以续期。
     * 采样、内存状态与 Redis 写入不构成事务；发布失败只记日志，内存决定不回滚，下轮继续尝试。
     *
     * @see AdmissionFeedbackCollector#collect()
     * @see AdaptiveRateController#evaluate
     */
    @Scheduled(fixedDelayString = "${voucherx.admission.controller.interval-millis:2000}")
    public void tick() {
        if (properties.mode() != AdmissionMode.ADAPTIVE) {
            return;
        }
        FeedbackSample sample = collector.collect();
        AdaptiveRateController.Decision decision = controller.evaluate(sample, System.nanoTime());
        if (decision.changed() || !"HOLD".equals(decision.reason())
                && !"IDLE_HOLD".equals(decision.reason())) {
            log.info("准入自适应调整: rate={}, capacity={}, reason={}, queueReady={}, inFlight={}, completedTps={}",
                    decision.rate(), decision.capacity(), decision.reason(),
                    sample.queueReady(), sample.inFlight(), sample.completedTps());
        }
        publish(decision.reason());
    }

    /**
     * 以应用当前 Unix 毫秒加 controller.policyTtlMillis 得到 validUntil，容量取配置共享上限与 max(1,
     * rate) 的较小者。
     * <p>
     * 将当前内存速率交给策略存储层；返回失败记错误日志，不伪造成功，也不永久放宽旧策略有效期。
     * 控制器使用的是 controller 的 TTL，不是 global.policyTtlMillis。
     *
     * @param reason
     *                   本次发布的诊断原因，例如 INITIAL、OVERLOAD 或 HOLD
     */
    private void publish(String reason) {
        long validUntil = System.currentTimeMillis()
                + Math.max(1, properties.controller().policyTtlMillis());
        boolean success = policyStore.updateShared(controller.currentRate(),
                Math.min(properties.global().sharedCapacity(),
                        Math.max(1.0, controller.currentRate())),
                validUntil);
        if (!success) {
            log.error("发布自适应准入策略失败，reason={}, rate={}", reason,
                    controller.currentRate());
        }
    }
}
