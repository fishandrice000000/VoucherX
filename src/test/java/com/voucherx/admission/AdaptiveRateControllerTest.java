package com.voucherx.admission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveRateControllerTest {

    private static final AdmissionProperties.Controller CONFIG = new AdmissionProperties.Controller(2000, 100, 10, 400,
            10, 0.5,
            1000, 100, 0.9, 3, 10_000, 10_000);

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

    @Test
    void lowArrivalWithoutQueueDoesNotLookLikeCapacityLag() {
        AdaptiveRateController controller = new AdaptiveRateController(CONFIG, 200);
        // 允许速率 100，但只有 122 个完成/2s=61 TPS；队列为空、只有一个在途。
        // 这是需求不足，不是下游过载，不能触发降速。
        AdaptiveRateController.Decision decision = controller.evaluate(sample(2_000, 122, 0, 1, 0), 1_000_000_000L);
        assertThat(decision.rate()).isEqualTo(100);
        assertThat(decision.changed()).isFalse();
    }

    @Test
    void idleSystemDoesNotAccelerateToMaximum() {
        AdaptiveRateController controller = new AdaptiveRateController(CONFIG, 200);
        long now = 1_000_000_000L;
        for (int i = 0; i < 10; i++) {
            controller.evaluate(sample(2_000, 0, 0, 0, 0), now + i);
        }
        assertThat(controller.currentRate()).isEqualTo(100);
    }

    @Test
    void continuousCompletionLagLowersRate() {
        AdaptiveRateController controller = new AdaptiveRateController(CONFIG, 200);
        FeedbackSample lagging = sample(2_000, 10, 50, 2, 0);

        AdaptiveRateController.Decision decision = controller.evaluate(lagging, 1_000_000_000L);

        assertThat(decision.rate()).isEqualTo(50);
        assertThat(decision.reason()).isEqualTo("OVERLOAD");
    }

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

    @Test
    void rateNeverExceedsMaximum() {
        AdaptiveRateController controller = new AdaptiveRateController(
                new AdmissionProperties.Controller(2000, 395, 10, 400, 10, 0.5,
                        1000, 100, 0.9, 1, 0, 10_000),
                200);
        AdaptiveRateController.Decision decision = controller.evaluate(sample(2_000, 800, 0, 10, 0), 1_000_000_000L);
        assertThat(decision.rate()).isEqualTo(400);
    }

    private FeedbackSample sample(long windowMillis, long completed, long ready,
            int inFlight, int hikariPending) {
        return new FeedbackSample(windowMillis, completed, 0, 0, 0, 1,
                inFlight, inFlight, ready, true, 1, hikariPending, true);
    }
}
