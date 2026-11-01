package com.voucherx.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试/实验专用的事务延迟注入。通过本地文件切换延迟，不暴露产品 HTTP 接口。
 *
 * 仅在 experiment.enabled=true 时生效；文件不存在时使用配置的初始延迟。
 * 每 100ms 最多读取一次文件，避免每个订单都产生文件 I/O。
 */
@Component
public class TransactionDelay {

    private static final Logger log = LoggerFactory.getLogger(TransactionDelay.class);
    private static final long REFRESH_NANOS = 100_000_000L;
    private static final long MAX_DELAY_MILLIS = 10_000L;

    private final boolean enabled;
    private final long initialDelayMillis;
    private final Path delayFile;
    private volatile long cachedDelayMillis;
    private volatile long lastReadNanos;

    public TransactionDelay(ExperimentProperties properties) {
        this.enabled = properties.enabled();
        this.initialDelayMillis = Math.min(properties.transactionDelayMillis(), MAX_DELAY_MILLIS);
        this.delayFile = Path.of(properties.delayFile());
        this.cachedDelayMillis = initialDelayMillis;
        if (enabled) {
            log.warn("实验事务延迟注入已启用，初始延迟 {}ms，控制文件 {}",
                    initialDelayMillis, delayFile);
        }
    }

    public long currentDelayMillis() {
        if (!enabled) {
            return 0;
        }
        long now = System.nanoTime();
        if (now - lastReadNanos >= REFRESH_NANOS) {
            lastReadNanos = now;
            cachedDelayMillis = readDelayFile();
        }
        return cachedDelayMillis;
    }

    public void apply() {
        long delay = currentDelayMillis();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("实验事务延迟被中断", ex);
        }
    }

    private long readDelayFile() {
        try {
            if (!Files.exists(delayFile)) {
                return initialDelayMillis;
            }
            String value = Files.readString(delayFile).trim();
            if (value.isEmpty()) {
                return 0;
            }
            long parsed = Long.parseLong(value);
            return Math.max(0, Math.min(MAX_DELAY_MILLIS, parsed));
        } catch (Exception ex) {
            log.warn("读取实验延迟文件失败，沿用初始延迟 {}ms: {}", initialDelayMillis, ex.getMessage());
            return initialDelayMillis;
        }
    }
}
