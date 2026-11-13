package com.voucherx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 秒杀链路配置，绑定 voucherx.seckill；默认值由 application.yml 提供。
 * <p>
 * BUCKET 的 bucketAttempts 限制单次建单访问多少个候选桶，不决定桶总数，也不自动启用分桶。
 *
 * @param publishConfirmTimeoutMillis
 *                                        等待 RabbitMQ 发布确认的超时，毫秒，项目配置默认 5000
 * @param requestStatusTtlSeconds
 *                                        Redis 辅助状态更新时使用的有效期，秒，默认 86400；预留脚本另用
 *                                        PERSIST 保留活跃依据
 * @param bucketAttempts
 *                                        单次建单允许尝试的候选桶数，默认 3；实际按至少 1、至多桶总数限幅
 * @see com.voucherx.order.service.OrderPersistenceService
 */
@ConfigurationProperties(prefix = "voucherx.seckill")
public record SeckillProperties(
        long publishConfirmTimeoutMillis,
        long requestStatusTtlSeconds,
        int bucketAttempts) {
}
