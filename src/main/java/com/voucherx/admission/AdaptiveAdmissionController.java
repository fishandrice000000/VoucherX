package com.voucherx.admission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期采样并发布共享 AIMD 速率。策略带 validUntil，控制器停止更新时新请求会在
 * 有效期后收到 503，而不是永久使用旧策略放行。
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

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (properties.mode() != AdmissionMode.ADAPTIVE) {
            return;
        }
        publish("INITIAL");
    }

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
