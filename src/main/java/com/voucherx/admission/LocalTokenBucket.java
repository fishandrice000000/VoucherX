package com.voucherx.admission;

import java.util.function.LongSupplier;

/**
 * 单实例、线程安全的内存令牌桶，计时默认使用单调时钟 nanoTime。
 * <p>
 * 构造时装满 capacity；申请时按经过秒数乘 ratePerSecond 补充，限制到容量后再尝试扣 1。
 * 无令牌立即拒绝，不让 Web 线程等待；实例重启会重新得到满桶，跨实例预算仍需 Redis。
 *
 * @see LocalAdmissionGate
 */
public final class LocalTokenBucket {

    /**
     * 每秒补充的令牌数量；直接构造桶时允许为 0，此时消耗初始令牌后不再补充。
     */
    private final double ratePerSecond;
    /**
     * 桶最多保存的令牌数，限制突发而非库存总量。
     */
    private final double capacity;
    /**
     * 提供纳秒刻度的单调时钟，测试可注入受控时钟。
     */
    private final LongSupplier nanoClock;
    /**
     * 上次申请结算后剩余令牌，可为小数；由同步方法保护。
     */
    private double tokens;
    /**
     * 上次申请时记录的时钟刻度，用于只结算两次申请之间的时间。
     */
    private long lastNanos;

    /**
     * 使用系统单调时钟创建满桶；不启动后台补充线程，令牌在申请时按需结算。
     *
     * @param ratePerSecond
     *                          每秒补充令牌数，有限且非负
     * @param capacity
     *                          桶容量，有限且为正
     */
    public LocalTokenBucket(double ratePerSecond, double capacity) {
        this(ratePerSecond, capacity, System::nanoTime);
    }

    /**
     * 创建使用指定纳秒时钟的满桶，便于确定性测试补充和回退行为。
     *
     * @param ratePerSecond
     *                          每秒补充令牌数
     * @param capacity
     *                          最大令牌数，可为小数
     * @param nanoClock
     *                          每次读取返回纳秒刻度的时钟
     * @throws IllegalArgumentException
     *                                      速率非有限/为负，或容量非有限/非正
     */
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

    /**
     * 在同步块保护的整个方法中完成计时、补充及扣减，竞争线程不能同时花掉同一枚令牌。
     * <p>
     * 补充公式为 min(capacity, tokens + elapsed/1e9*ratePerSecond)，负经过时间按 0 处理，
     * 无论通过还是拒绝都更新 lastNanos。余额不足 1 时返回 false，使用小数比较容差处理浮点误差。
     * <p>
     * 例如速率 1/秒、容量 2，同一时刻只能通过两次；0.5 秒后只有 0.5 枚，再过 0.5 秒才可通过一次。
     *
     * @return 扣减一个令牌成功为 true，预算不足为 false
     */
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

    /**
     * 只读出上次申请结算后的余额，读取本身不按当前时间补充令牌。
     *
     * @return 缓存的令牌余额，不是重新结算后的即时预算
     */
    public synchronized double availableTokens() {
        return tokens;
    }
}
