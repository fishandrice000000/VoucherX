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
 * 把本实例消费者完成计数与外部负载读数组装成反馈。
 * <p>
 * RabbitAdmin 采集主队列 ready，Hikari MXBean 采集连接活跃数及等待线程数。
 * 完成 TPS 来自 CompletionMetrics；ready 的统计范围是等待交付的主队列消息。
 * <p>
 * 每项观测携带 measured 标志；measured=false 表示采集缺失，此时数值 0 仅为占位。
 *
 * @see CompletionMetrics#snapshotAndReset()
 * @see FeedbackSample
 * @see AdaptiveAdmissionController#tick()
 */
@Component
public class AdmissionFeedbackCollector {

    private static final Logger log = LoggerFactory.getLogger(AdmissionFeedbackCollector.class);

    private final CompletionMetrics completionMetrics;
    private final ObjectProvider<RabbitAdmin> rabbitAdminProvider;
    private final HikariPoolMXBean hikariBean;
    private long lastSampleNanos;

    /**
     * 保存指标和 RabbitAdmin 提供者；若数据源是 Hikari，则在构造时尝试保存其池 MXBean，并记录采样起点。
     * <p>
     * 之后不会逐轮重新获取 MXBean，未取得时使用 hikariMeasured=false。
     *
     * @param completionMetrics
     *                                本实例消费者累计的窗口计数
     * @param rabbitAdminProvider
     *                                可选 RabbitAdmin，用于主队列信息
     * @param dataSource
     *                                应用数据源，仅获取运行时池状态，不执行 SQL
     */
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

    /**
     * 以单调时钟测出上次采样以来的窗口长度（至少 1 毫秒），取出并重置消费者计数。
     * <p>
     * 再尽力读取主队列 ready 和 Hikari 活跃/等待数，分别记录观测是否成功；队列或池查询异常记调试日志。
     * 这些读数来自不同瞬间，不是数据库事务或跨系统一致快照；计数已被取走，不应多个采集器重复消费同一窗口。
     * 本方法自身不判断是否健康，也不修改入口令牌。
     *
     * @return 包含完成量、执行计数、负载及观测标志的窗口样本
     * @see AdaptiveRateController#evaluate
     */
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
