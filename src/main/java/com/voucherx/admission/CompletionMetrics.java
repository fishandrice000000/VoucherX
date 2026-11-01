package com.voucherx.admission;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消费者实际完成订单的轻量窗口计数。
 *
 * 只有事务代理成功返回且 created=true 才计入 completed；重复消息、业务失败和
 * 临时异常分别计数。事务耗时覆盖代理调用（含提交/回滚）。
 */
@Component
public class CompletionMetrics {

    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong businessFailures = new AtomicLong();
    private final AtomicLong transactionNanos = new AtomicLong();
    private final AtomicLong transactionCount = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    public long begin() {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        return System.nanoTime();
    }

    public void recordCreated(long startedNanos) {
        completed.incrementAndGet();
        recordDuration(startedNanos);
    }

    public void recordDuplicate(long startedNanos) {
        duplicates.incrementAndGet();
        recordDuration(startedNanos);
    }

    public void recordFailure(long startedNanos) {
        failures.incrementAndGet();
        recordDuration(startedNanos);
    }

    /**
     * 明确的业务失败（如售罄、用户重复）不计入基础设施失败率，避免控制器把
     * 库存耗尽误判为处理能力下降。
     */
    public void recordBusinessFailure(long startedNanos) {
        businessFailures.incrementAndGet();
        recordDuration(startedNanos);
    }

    public void end() {
        inFlight.decrementAndGet();
    }

    public Window snapshotAndReset() {
        long completedCount = completed.getAndSet(0);
        long duplicateCount = duplicates.getAndSet(0);
        long failureCount = failures.getAndSet(0);
        long businessFailureCount = businessFailures.getAndSet(0);
        long durationNanos = transactionNanos.getAndSet(0);
        long durationCount = transactionCount.getAndSet(0);
        int current = inFlight.get();
        int peak = maxInFlight.getAndSet(current);
        return new Window(completedCount, duplicateCount, failureCount, businessFailureCount,
                durationNanos, durationCount, current, peak);
    }

    public int inFlight() {
        return inFlight.get();
    }

    private void recordDuration(long startedNanos) {
        long elapsed = Math.max(0, System.nanoTime() - startedNanos);
        transactionNanos.addAndGet(elapsed);
        transactionCount.incrementAndGet();
    }

    public record Window(
            long completed,
            long duplicates,
            long failures,
            long businessFailures,
            long transactionNanos,
            long transactionCount,
            int inFlight,
            int maxInFlight
    ) {
        public double averageTransactionMillis() {
            return transactionCount == 0 ? 0 : transactionNanos / 1_000_000.0 / transactionCount;
        }

        public long totalOutcomes() {
            return completed + duplicates + failures;
        }
    }
}
