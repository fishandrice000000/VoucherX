package com.voucherx.fulfillment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 03 的 Outbox 调度与消费批次配置，绑定 voucherx.delivery。
 * <p>
 * 扫描批次、RabbitMQ 预取、消费者聚合及数据库事务批次是不同层次；batchEnabled 默认关闭。
 * 事件尝试次数按成功认领累计，包含确认结果未知或发送前中断的尝试，不等于订单事务执行次数。
 *
 * @param enabled
 *                            是否允许 tick 自动投递；显式调用 dispatch 不检查此开关，默认 true
 * @param scanSize
 *                            每轮最多扫描的候选事件数，默认 32
 * @param leaseMillis
 *                            一次认领的租约时长，毫秒，默认 30000
 * @param maxAttempts
 *                            失败发布/消费重试路径的认领尝试预算，默认 8
 * @param retryMillis
 *                            基础重试等待，毫秒，默认 1000；发布失败做指数退避，消费 defer 使用固定等待
 * @param batchEnabled
 *                            是否尝试一个事务处理多条消息，默认 false
 * @param batchSize
 *                            聚合及消费拆分的数量上限，默认 16
 * @param batchBytes
 *                            单条及聚合消息体的字节上限，默认 1048576（1 MiB）
 * @param batchWaitMillis
 *                            监听器接收等待配置，毫秒，默认 50；不是整批事务耗时上限
 * @see OutboxDispatcher
 * @see DeliveryListenerConfig
 * @see com.voucherx.mq.consumer.SeckillOrderConsumer
 */
@ConfigurationProperties("voucherx.delivery")
public record DeliveryProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("32") int scanSize, @DefaultValue("30000") long leaseMillis,
        @DefaultValue("8") int maxAttempts, @DefaultValue("1000") long retryMillis,
        @DefaultValue("false") boolean batchEnabled, @DefaultValue("16") int batchSize,
        @DefaultValue("1048576") int batchBytes, @DefaultValue("50") int batchWaitMillis) {
    /**
     * 验证扫描、租约及批次参数的基本范围；默认构造值来自记录组件的 DefaultValue。
     *
     * @param enabled
     *                            自动投递开关
     * @param scanSize
     *                            每轮候选数，1 到 1000
     * @param leaseMillis
     *                            租约毫秒，至少 1000
     * @param maxAttempts
     *                            最大认领尝试预算，至少 1
     * @param retryMillis
     *                            基础重试毫秒，至少 1
     * @param batchEnabled
     *                            批事务开关
     * @param batchSize
     *                            聚合数量，1 到 100
     * @param batchBytes
     *                            消息体字节上限，至少 1024
     * @param batchWaitMillis
     *                            接收等待毫秒，1 到 1000
     * @throws IllegalArgumentException
     *                                      参数超出构造器规定范围
     */
    public DeliveryProperties {
        if (scanSize < 1 || scanSize > 1000 || leaseMillis < 1000 || maxAttempts < 1 || retryMillis < 1
                || batchSize < 1 || batchSize > 100 || batchBytes < 1024 || batchWaitMillis < 1
                || batchWaitMillis > 1000)
            throw new IllegalArgumentException("无效的投递/批量配置");
    }

    /**
     * 按认领尝试次数计算发布失败等待：基础等待乘 2 的 attempt-1 次方，指数限制在 0 到 10，结果再取与 60000 的较小值。
     * <p>
     * 默认基础 1000 毫秒时依次为 1、2、4 秒等，最多 60 秒；消费 defer 使用固定 retryMillis，不调用此方法。
     *
     * @param attempt
     *                    当前累计认领次数，正常从 1 开始
     * @return 以毫秒表示的发布重试等待
     */
    public long backoff(int attempt) {
        return Math.min(60000, retryMillis * (1L << Math.min(10, Math.max(0, attempt - 1))));
    }
}
