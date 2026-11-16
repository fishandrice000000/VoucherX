package com.voucherx.admission;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消费者本实例的轻量窗口统计；正常建单路径在事务代理返回且 created=true 后才增加完成数。
 * <p>
 * 重复消息、暂时性失败和明确业务失败分别计数，避免把重复消费或库存耗尽当作新增完成量。
 * 在途数统计消费者调用段：单条通常一次执行，批处理时一次事务批次，不是消息队列全部 unacked。
 * <p>
 * 耗时从 begin 返回的时刻算到结果记录，覆盖代理提交/回滚及记录前开销；批处理每个结果沿用批次起点，
 * 不是逐订单独立 SQL 耗时。故障注入或进程中断也可能使已提交订单尚未来得及计数，数据库才是业务事实。
 *
 * @see com.voucherx.mq.consumer.SeckillOrderConsumer
 * @see AdmissionFeedbackCollector
 */
@Component
public class CompletionMetrics {

    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong businessFailures = new AtomicLong();
    private final AtomicLong observedDurationNanos = new AtomicLong();
    private final AtomicLong observedDurationSampleCount = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /**
     * 增加当前执行数、更新观测峰值并返回单调起点；调用者需在 finally 中配对 end。
     *
     * @return 供结果记录计算经过时间的纳秒起点
     */
    public long begin() {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        return System.nanoTime();
    }

    /**
     * 在事务服务正常返回且本次新建订单后增加完成数，并记录从执行起点到此刻的耗时。
     *
     * @param startedNanos
     *                         begin 返回的单调纳秒起点
     */
    public void recordCreated(long startedNanos) {
        completed.incrementAndGet();
        recordDuration(startedNanos);
    }

    /**
     * 记录复用既有订单的结果，不增加新订单完成数，同时累加本次观察耗时。
     *
     * @param startedNanos
     *                         begin 返回的单调纳秒起点
     */
    public void recordDuplicate(long startedNanos) {
        duplicates.incrementAndGet();
        recordDuration(startedNanos);
    }

    /**
     * 记录消费者调用中的暂时性异常；计入基础设施失败比例，批事务失败后逐条降级可能另有结果记录。
     *
     * @param startedNanos
     *                         本次单条或批次调用起点
     */
    public void recordFailure(long startedNanos) {
        failures.incrementAndGet();
        recordDuration(startedNanos);
    }

    /**
     * 记录售罄、用户重复等业务拒绝和耗时，单独计数，不进入基础设施失败比例的分子或分母。
     *
     * @param startedNanos
     *                         本次执行的单调纳秒起点
     */
    public void recordBusinessFailure(long startedNanos) {
        businessFailures.incrementAndGet();
        recordDuration(startedNanos);
    }

    /**
     * 减少当前执行数，不记录业务结果；应与 begin 配对，不能当作已成功提交或已 ACK。
     */
    public void end() {
        inFlight.decrementAndGet();
    }

    /**
     * 分别用 getAndSet 取走完成、重复、失败、业务失败和耗时计数，留给下一窗口重新累积。
     * <p>
     * 在途执行数不清零，峰值起点重置为当前执行数；各 Atomic 字段独立取值，不是所有字段同时冻结的一致快照。
     * 并发记录可能跨越窗口边界，适合反馈观测，不承担财务或订单审计计数。
     *
     * @return 刚取走的计数、累计耗时以及当前/峰值在途执行数
     */
    public Window snapshotAndReset() {
        long completedCount = completed.getAndSet(0);
        long duplicateCount = duplicates.getAndSet(0);
        long failureCount = failures.getAndSet(0);
        long businessFailureCount = businessFailures.getAndSet(0);
        long durationNanos = observedDurationNanos.getAndSet(0);
        long durationCount = observedDurationSampleCount.getAndSet(0);
        int current = inFlight.get();
        int peak = maxInFlight.getAndSet(current);
        return new Window(completedCount, duplicateCount, failureCount, businessFailureCount,
                durationNanos, durationCount, current, peak);
    }

    /**
     * 读取本实例当前已 begin 尚未 end 的消费者调用段数量。
     *
     * @return 当前执行段数，不是请求总数或主队列积压数
     */
    public int inFlight() {
        return inFlight.get();
    }

    /**
     * 把非负经过纳秒累加到耗时总量并增加一次耗时样本；只保存统计，不控制限流。
     *
     * @param startedNanos
     *                         begin 返回的单调纳秒起点
     */
    private void recordDuration(long startedNanos) {
        long elapsed = Math.max(0, System.nanoTime() - startedNanos);
        observedDurationNanos.addAndGet(elapsed);
        observedDurationSampleCount.incrementAndGet();
    }

    /**
     * 从计数器取走的一个近似窗口，供采集器与外部负载观测组合；并发取值不保证各字段严格同一时刻。
     *
     * @param completed
     *                                        观察到的新建订单数
     * @param duplicates
     *                                        复用既有订单的结果数
     * @param failures
     *                                        暂时性或基础设施异常结果数
     * @param businessFailures
     *                                        明确业务拒绝数，独立于基础设施失败率
     * @param observedDurationNanos
     *                                        已记录结果的累计经过纳秒
     * @param observedDurationSampleCount
     *                                        累计耗时样本数，批处理时不等于独立事务数
     * @param inFlight
     *                                        采样时已开始未结束的执行段数
     * @param maxInFlight
     *                                        上个窗口观察到的执行段峰值
     */
    public record Window(
            long completed,
            long duplicates,
            long failures,
            long businessFailures,
            long observedDurationNanos,
            long observedDurationSampleCount,
            int inFlight,
            int maxInFlight) {
        /**
         * 将累计纳秒除以耗时样本数得到平均毫秒；批处理复用开始时刻，因此不能当作单笔 SQL 平均时间。
         *
         * @return 观察耗时平均毫秒数；无样本为 0
         */
        public double averageObservedDurationMillis() {
            return observedDurationSampleCount == 0 ? 0
                    : observedDurationNanos / 1_000_000.0 / observedDurationSampleCount;
        }

        /**
         * 合计新建、重复和暂时性失败结果，刻意排除明确业务失败，与基础设施失败比例分母一致。
         *
         * @return 参与该失败比例的结果数
         */
        public long infrastructureOutcomeCount() {
            return completed + duplicates + failures;
        }
    }
}
