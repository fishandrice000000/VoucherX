package com.voucherx.admission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AIMD 的确定性单元测试：直接给反馈与单调时间，隔离调度和 Redis 发布。
 * <p>
 * 本类 CONFIG 的 beta 为 0.5，所以过载示例是 100→50；项目默认 beta=0.8，对应指南的 100→80。
 * 这里验证分支及边界，没有证明实际吞吐收益或完整跨组件反馈闭环。
 */
class AdaptiveRateControllerTest {

    private static final AdmissionProperties.Controller CONFIG = new AdmissionProperties.Controller(2000, 100, 10, 400,
            10, 0.5,
            1000, 100, 0.9, 3, 10_000, 10_000);

    /**
     * ready=1500 超过高水位，以本测试 beta=0.5 将速率 100→50；冷却内的健康样本不能立即升回。
     */
    @Test
    void highQueueLowersRateAndStartsCooldown() {
        AdaptiveRateController controller = new AdaptiveRateController(CONFIG, 200);
        FeedbackSample overload = sample(2_000, 100, 1500, 0, 0);
        long now = 1_000_000_000L;

        AdaptiveRateController.Decision decision = controller.evaluate(overload, now);

        assertThat(decision.rate()).isEqualTo(50);
        assertThat(decision.changed()).isTrue();
        assertThat(decision.reason()).isEqualTo("OVERLOAD");
        assertThat(decision.capacity()).isEqualTo(50);

        AdaptiveRateController.Decision healthyDuringCooldown = controller.evaluate(sample(2_000, 100, 0, 0, 0),
                now + 1_000_000L);
        assertThat(healthyDuringCooldown.rate()).isEqualTo(50);
    }

    /**
     * 连续三个有效、低队列且确有完成量的健康样本，将速率按 alpha=10 从 100→110。
     */
    @Test
    void increasesAfterConsecutiveHealthyWindows() {
        AdaptiveRateController controller = new AdaptiveRateController(CONFIG, 200);
        long now = 1_000_000_000L;

        assertThat(controller.evaluate(sample(2_000, 200, 0, 10, 0), now).rate())
                .isEqualTo(100);
        assertThat(controller.evaluate(sample(2_000, 200, 0, 10, 0), now + 1).rate())
                .isEqualTo(100);
        AdaptiveRateController.Decision third = controller.evaluate(sample(2_000, 220, 0, 10, 0), now + 2);
        assertThat(third.rate()).isEqualTo(110);
        assertThat(third.reason()).isEqualTo("HEALTHY");
    }

    /**
     * 允许速率 100、实际只完成 61 TPS，但 ready=0 且仍有一个在途，验证低需求不被当作队列处理落后。
     */
    @Test
    void lowArrivalWithoutQueueDoesNotLookLikeCapacityLag() {
        AdaptiveRateController controller = new AdaptiveRateController(CONFIG, 200);
        // 允许速率 100，但只有 122 个完成/2s=61 TPS；队列为空、只有一个在途。
        // 这是需求不足，不是下游过载，不能触发降速。
        AdaptiveRateController.Decision decision = controller.evaluate(sample(2_000, 122, 0, 1, 0), 1_000_000_000L);
        assertThat(decision.rate()).isEqualTo(100);
        assertThat(decision.changed()).isFalse();
    }

    /**
     * 连续十个无队列且无完成量的空闲窗口保持速率 100，避免空闲时自动加到上限。
     */
    @Test
    void idleSystemDoesNotAccelerateToMaximum() {
        AdaptiveRateController controller = new AdaptiveRateController(CONFIG, 200);
        long now = 1_000_000_000L;
        for (int i = 0; i < 10; i++) {
            controller.evaluate(sample(2_000, 0, 0, 0, 0), now + i);
        }
        assertThat(controller.currentRate()).isEqualTo(100);
    }

    /**
     * 队列 ready=50 尚未到高水位，但 2 秒仅完成 10 单，验证存在积压时的完成落后可触发 100→50。
     */
    @Test
    void continuousCompletionLagLowersRate() {
        AdaptiveRateController controller = new AdaptiveRateController(CONFIG, 200);
        FeedbackSample lagging = sample(2_000, 10, 50, 2, 0);

        AdaptiveRateController.Decision decision = controller.evaluate(lagging, 1_000_000_000L);

        assertThat(decision.rate()).isEqualTo(50);
        assertThat(decision.reason()).isEqualTo("OVERLOAD");
    }

    /**
     * 队列未成功测量的无效样本先保持两次，第三次采用最小速率 10；不是把缺失队列值当作真实零积压。
     */
    @Test
    void invalidMetricsDoNotIncreaseAndEventuallyUseMinimum() {
        AdaptiveRateController controller = new AdaptiveRateController(CONFIG, 200);
        FeedbackSample invalid = new FeedbackSample(2_000, 100, 0, 0, 0, 1,
                0, 0, 0, false, 0, 0, false);
        long now = 1_000_000_000L;

        assertThat(controller.evaluate(invalid, now).rate()).isEqualTo(100);
        assertThat(controller.evaluate(invalid, now + 1).rate()).isEqualTo(100);
        AdaptiveRateController.Decision third = controller.evaluate(invalid, now + 2);
        assertThat(third.rate()).isEqualTo(10);
        assertThat(third.reason()).isEqualTo("METRICS_INVALID");
    }

    /**
     * 从 395 健康上调 10，验证结果限制为 maxRate=400。
     */
    @Test
    void rateNeverExceedsMaximum() {
        AdaptiveRateController controller = new AdaptiveRateController(
                new AdmissionProperties.Controller(2000, 395, 10, 400, 10, 0.5,
                        1000, 100, 0.9, 1, 0, 10_000),
                200);
        AdaptiveRateController.Decision decision = controller.evaluate(sample(2_000, 800, 0, 10, 0), 1_000_000_000L);
        assertThat(decision.rate()).isEqualTo(400);
    }

    /**
     * 构造队列和 Hikari 均测得的简化反馈；失败、重复和业务失败默认为 0，便于只测试指定控制信号。
     *
     * @param windowMillis
     *                          采样间隔，毫秒
     * @param completed
     *                          新建订单完成数
     * @param ready
     *                          主队列待交付消息数
     * @param inFlight
     *                          当前执行段数，同时用于测试峰值
     * @param hikariPending
     *                          等待数据库连接线程数
     * @return 用于纯状态机测试的反馈样本
     */
    private FeedbackSample sample(long windowMillis, long completed, long ready,
            int inFlight, int hikariPending) {
        return new FeedbackSample(windowMillis, completed, 0, 0, 0, 1,
                inFlight, inFlight, ready, true, 1, hikariPending, true);
    }
}
