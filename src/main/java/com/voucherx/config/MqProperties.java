package com.voucherx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 保留的 RabbitMQ 重试拓扑配置，绑定 voucherx.mq。
 * <p>
 * 当前消费者暂时性失败通过 RequestLedger.defer 安排数据库 Outbox 重发；预算使用
 * DeliveryProperties.maxAttempts。
 * 这里的 maxRetries 当前没有生产读取调用，不能当作本轮 Outbox 最大尝试次数。
 *
 * @param maxRetries
 *                           保留的旧重试次数配置，默认配置为 3；当前 Outbox 主线不使用
 * @param retryTtlMillis
 *                           保留重试队列的消息 TTL，毫秒，默认配置为 5000
 * @see com.voucherx.mq.config.RabbitMqConfig#seckillRetryQueue
 * @see com.voucherx.fulfillment.DeliveryProperties
 */
@ConfigurationProperties(prefix = "voucherx.mq")
public record MqProperties(
        int maxRetries,
        long retryTtlMillis) {
}
