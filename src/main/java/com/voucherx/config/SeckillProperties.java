package com.voucherx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 秒杀链路配置，绑定 voucherx.seckill；默认值由 application.yml 提供。
 * <p>
 * BUCKET 的 bucketAttempts 限制单次建单访问多少个候选桶，不决定桶总数，也不自动启用分桶。
 *
 * @param publishConfirmTimeoutMillis
 *                                        等待 RabbitMQ 发布确认的超时，毫秒，项目配置默认 5000
 * @param bucketAttempts
 *                                        单次建单允许尝试的候选桶数，默认 3；实际按至少 1、至多桶总数限幅
 * @see com.voucherx.order.service.PurchaseOrderCreationService
 */
@ConfigurationProperties(prefix = "voucherx.seckill")
public record SeckillProperties(
        long publishConfirmTimeoutMillis,
        int bucketAttempts) {
}
