package com.voucherx.admission;

import java.util.function.LongSupplier;

/**
 * 单实例令牌桶。使用单调时钟，线程安全；无令牌时立即返回 false，不阻塞 Web 线程。
 *
 * 这是单实例保护，不是多实例全局限制。实例重启或桶重建会重新获得一个满桶，
 * 因此额外突发上界是 capacity。
 */
public final class LocalTokenBucket {

    private final double ratePerSecond;
    private final double capacity;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastNanos;

    public LocalTokenBucket(double ratePerSecond, double capacity) {
        this(ratePerSecond, capacity, System::nanoTime);
    }

    public LocalTokenBucket(double ratePerSecond, double capacity, LongSupplier nanoClock) {
        if (!Double.isFinite(ratePerSecond) || ratePerSecond < 0) {
            throw new IllegalArgumentException("local rate must be finite and non-negative");
        }
        if (!Double.isFinite(capacity) || capacity <= 0) {
            throw new IllegalArgumentException("local capacity must be finite and positive");
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastNanos = nanoClock.getAsLong();
    }

    public synchronized boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastNanos;
        if (elapsed < 0) {
            // 时钟回退时不返还令牌，也不把负经过时间用于补充。
            elapsed = 0;
        }
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed / 1_000_000_000.0 * ratePerSecond);
        }
        lastNanos = now;
        if (tokens + 1e-9 < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }

    public synchronized double availableTokens() {
        return tokens;
    }
}
