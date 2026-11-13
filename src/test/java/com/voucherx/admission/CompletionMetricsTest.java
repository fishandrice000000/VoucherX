package com.voucherx.admission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费者窗口统计的单元测试，验证新建、业务拒绝与基础设施异常分开累计，以及取走窗口后的重置。
 */
class CompletionMetricsTest {

    /**
     * 分别记录一次新建、业务失败和暂时性失败，再取窗口核对各字段；第二次快照应没有重复消费已取走的计数。
     */
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
