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

class LocalTokenBucketTest {

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

    @Test
    void clockRollbackDoesNotAddTokens() {
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        LocalTokenBucket bucket = new LocalTokenBucket(100.0, 1.0, clock::get);

        assertThat(bucket.tryAcquire()).isTrue();
        clock.addAndGet(-5_000_000_000L);
        assertThat(bucket.tryAcquire()).isFalse();
    }

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
