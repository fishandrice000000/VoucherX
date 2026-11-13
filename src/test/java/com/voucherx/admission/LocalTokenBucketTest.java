package com.voucherx.admission;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地令牌桶的纯单元测试：注入受控纳秒时钟，分别验证按时补充、回退处理和同步竞争，不启动 Redis。
 */
class LocalTokenBucketTest {

    /**
     * 容量 2、速率 1/秒：同一刻通过两次后拒绝，推进受控时钟 1 秒后只再通过一次。
     */
    @Test
    void refillsByElapsedTimeAndNeverExceedsCapacity() {
        AtomicLong clock = new AtomicLong();
        LocalTokenBucket bucket = new LocalTokenBucket(1.0, 2.0, clock::get);

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();

        clock.addAndGet(1_000_000_000L);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    /**
     * 消耗唯一令牌后把时钟退回 5 秒，验证负经过时间不会凭空补充令牌。
     */
    @Test
    void clockRollbackDoesNotAddTokens() {
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        LocalTokenBucket bucket = new LocalTokenBucket(100.0, 1.0, clock::get);

        assertThat(bucket.tryAcquire()).isTrue();
        clock.addAndGet(-5_000_000_000L);
        assertThat(bucket.tryAcquire()).isFalse();
    }

    /**
     * 固定时钟不前进，16 个线程各申请 20 次，验证 synchronized 保护下总通过数恰为初始容量 25。
     *
     * @throws Exception
     *                       线程终止等待被中断等测试异常
     */
    @Test
    void concurrentAcquireNeverExceedsCapacityWithoutElapsedTime() throws Exception {
        LocalTokenBucket bucket = new LocalTokenBucket(1.0, 25.0, () -> 0L);
        int threads = 16;
        int attemptsPerThread = 20;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < attemptsPerThread; j++) {
                            if (bucket.tryAcquire()) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(allowed.get()).isEqualTo(25);
    }

    /**
     * 验证负速率、零容量及 NaN 速率在构造时被拒绝，避免产生不可解释的令牌运算。
     */
    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new LocalTokenBucket(-1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalTokenBucket(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalTokenBucket(Double.NaN, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
