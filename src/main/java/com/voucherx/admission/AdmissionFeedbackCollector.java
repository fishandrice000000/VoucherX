package com.voucherx.admission;

import com.voucherx.mq.config.RabbitMqConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 控制器采样：完成 TPS 来自消费者事务返回点；队列只看主队列 ready；Hikari 使用
 * 运行时 MXBean，避免周期查询系统表给数据库增加负担。
 *
 * 当前 RabbitMQ classic queue 不提供可靠的最老消息年龄，因此不伪造该指标。
 */
@Component
public class AdmissionFeedbackCollector {

    private static final Logger log = LoggerFactory.getLogger(AdmissionFeedbackCollector.class);

    private final CompletionMetrics completionMetrics;
    private final ObjectProvider<RabbitAdmin> rabbitAdminProvider;
    private final HikariPoolMXBean hikariBean;
    private long lastSampleNanos;

    public AdmissionFeedbackCollector(CompletionMetrics completionMetrics,
            ObjectProvider<RabbitAdmin> rabbitAdminProvider,
            DataSource dataSource) {
        this.completionMetrics = completionMetrics;
        this.rabbitAdminProvider = rabbitAdminProvider;
        this.hikariBean = dataSource instanceof HikariDataSource hikari
                ? hikari.getHikariPoolMXBean()
                : null;
        this.lastSampleNanos = System.nanoTime();
    }

    public FeedbackSample collect() {
        long now = System.nanoTime();
        long windowMillis = Math.max(1, (now - lastSampleNanos) / 1_000_000);
        lastSampleNanos = now;
        CompletionMetrics.Window window = completionMetrics.snapshotAndReset();

        long ready = 0;
        boolean queueMeasured = false;
        RabbitAdmin rabbitAdmin = rabbitAdminProvider.getIfAvailable();
        try {
            if (rabbitAdmin != null) {
                QueueInformation info = rabbitAdmin.getQueueInfo(RabbitMqConfig.MAIN_QUEUE);
                if (info != null) {
                    ready = info.getMessageCount();
                    queueMeasured = true;
                }
            }
        } catch (Exception ex) {
            log.debug("读取 RabbitMQ 主队列 ready 数失败: {}", ex.getMessage());
        }

        int active = 0;
        int pending = 0;
        boolean hikariMeasured = false;
        if (hikariBean != null) {
            try {
                active = hikariBean.getActiveConnections();
                pending = hikariBean.getThreadsAwaitingConnection();
                hikariMeasured = true;
            } catch (Exception ex) {
                log.debug("读取 Hikari 池状态失败: {}", ex.getMessage());
            }
        }

        return new FeedbackSample(windowMillis, window.completed(), window.duplicates(),
                window.failures(), window.businessFailures(), window.averageTransactionMillis(),
                window.inFlight(), window.maxInFlight(), ready, queueMeasured,
                active, pending, hikariMeasured);
    }
}
