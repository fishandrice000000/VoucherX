package com.voucherx.admission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionMetricsTest {

    @Test
    void separatesCreatedOrdersFromBusinessAndInfrastructureFailures() {
        CompletionMetrics metrics = new CompletionMetrics();

        long created = metrics.begin();
        metrics.recordCreated(created);
        metrics.end();

        long business = metrics.begin();
        metrics.recordBusinessFailure(business);
        metrics.end();

        long failure = metrics.begin();
        metrics.recordFailure(failure);
        metrics.end();

        CompletionMetrics.Window window = metrics.snapshotAndReset();
        assertThat(window.completed()).isEqualTo(1);
        assertThat(window.businessFailures()).isEqualTo(1);
        assertThat(window.failures()).isEqualTo(1);
        assertThat(window.duplicates()).isZero();
        assertThat(window.inFlight()).isZero();
        assertThat(window.averageTransactionMillis()).isGreaterThanOrEqualTo(0);

        CompletionMetrics.Window empty = metrics.snapshotAndReset();
        assertThat(empty.completed()).isZero();
        assertThat(empty.failures()).isZero();
        assertThat(empty.businessFailures()).isZero();
    }
}
