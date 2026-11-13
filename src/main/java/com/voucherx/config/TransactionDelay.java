package com.voucherx.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用于库存锁竞争实验的事务内延迟器，默认关闭。
 * <p>
 * OrderPersistenceService 在成功扣减库存之后、插单之前调用 apply，使等待发生在持有库存行锁期间。
 * 它通过本地文件调整延迟，观察数据库变慢时的行为；不会自动评估分桶吞吐或宣告性能验收通过。
 * <p>
 * 开启时以约 100 毫秒间隔缓存文件读数，避免逐单读文件；这是并发下的尽力刷新，不是严格互斥限频。
 *
 * @see ExperimentProperties
 * @see com.voucherx.order.service.OrderPersistenceService#persist
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

    /**
     * 读取实验开关、初始延迟及控制文件路径，初始延迟至多 10000 毫秒；配置开启时输出提示日志。
     *
     * @param properties
     *                       经启动校验的实验配置
     */
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

    /**
     * 关闭实验时立即返回 0；开启时读取或复用文件延迟缓存。
     * <p>
     * 刷新时刻使用单调时钟 nanoTime，而非业务的 Unix 时间戳；volatile 提供可见性，刷新检查并非原子互斥。
     *
     * @return 本次应等待的毫秒数，范围 0 到 10000
     */
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

    /**
     * 按当前实验延迟休眠；未开启或延迟为 0 时直接返回。
     * <p>
     * 本方法自己不开事务，放到扣库存之后调用才会延长锁持有时间。
     * 被中断时恢复线程中断标记并抛异常，建单服务的事务因此回滚。
     *
     * @throws IllegalStateException
     *                                   实验等待被中断
     */
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

    /**
     * 读取控制文件：不存在或读取/解析失败时回退初始延迟，空内容表示 0。
     * <p>
     * 正常解析后把负数钳制为 0、过大值限制为 10000 毫秒；不会在这里修改任何业务库存。
     *
     * @return 限幅后的文件值，或构造时记录的初始延迟
     */
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
