package com.voucherx.seckill.model;

/**
 * 异步订单消息。messageId 用于链路追踪，requestId 用于业务幂等，二者不能混用。
 * stockMode / startBucketNo / layoutVersion 用于 MySQL 桶模式追踪；旧消息缺少这些字段时，
 * 只能用于 SINGLE 活动，不能解释为 BUCKET 请求。
 */
public record SeckillOrderMessage(
        String messageId,
        String requestId,
        Long userId,
        Long voucherId,
        long createdAt,
        String stockMode,
        Integer startBucketNo,
        Integer layoutVersion,
        long epoch) {
    public SeckillOrderMessage(String messageId, String requestId, Long userId, Long voucherId, long createdAt,
            String stockMode, Integer startBucketNo, Integer layoutVersion) {
        this(messageId, requestId, userId, voucherId, createdAt, stockMode, startBucketNo, layoutVersion, 1);
    }
}
