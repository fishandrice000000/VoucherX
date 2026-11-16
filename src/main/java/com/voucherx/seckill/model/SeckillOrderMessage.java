package com.voucherx.seckill.model;

/**
 * 由持久化请求构造的异步建单消息，主线入口为 RequestRecord.message。
 * <p>
 * eventId 对应固定 Outbox eventId，requestId
 * 标识业务请求；stockMode、startBucketNo、layoutVersion 保存计划布局。
 * epoch 标识活动恢复代次，消费者在建单事务中将消息完整身份与账本、活动配置核对。
 *
 * @param eventId
 *                          固定事件 ID，不是每次发布的 CorrelationData ID
 * @param requestId
 *                          抢券请求 ID
 * @param userId
 *                          抢券用户 ID
 * @param voucherId
 *                          活动 ID
 * @param createdAt
 *                          意图创建时刻，Unix 毫秒
 * @param stockMode
 *                          SINGLE 或 BUCKET
 * @param startBucketNo
 *                          计划起始桶，不是最终实际扣减桶
 * @param layoutVersion
 *                          活动库存布局版本
 * @param epoch
 *                          请求绑定的恢复代次
 */
public record SeckillOrderMessage(
        String eventId,
        String requestId,
        Long userId,
        Long voucherId,
        long createdAt,
        String stockMode,
        Integer startBucketNo,
        Integer layoutVersion,
        long epoch) {
}
