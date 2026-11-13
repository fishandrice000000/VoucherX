package com.voucherx.seckill.model;

/**
 * 从持久化请求生成的异步建单负载；主线由 RequestRecord.message 构造。
 * <p>
 * messageId 就是固定的 Outbox eventId，重发不变；requestId 是业务请求幂等键。
 * stockMode/startBucketNo/layoutVersion 是计划布局，epoch 防止恢复前的旧消息继续生效。
 * 历史缺失布局的消息只有满足单库存兼容校验才可能使用，账本守卫还会核对完整身份。
 *
 * @param messageId
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
        String messageId,
        String requestId,
        Long userId,
        Long voucherId,
        long createdAt,
        String stockMode,
        Integer startBucketNo,
        Integer layoutVersion,
        long epoch) {
    /**
     * 兼容未显式指定恢复代次的调用，以初始代次 1 构造消息；正式链路应传入账本记录的代次。
     *
     * @param messageId
     *                          固定事件 ID
     * @param requestId
     *                          抢券请求 ID
     * @param userId
     *                          用户 ID
     * @param voucherId
     *                          活动 ID
     * @param createdAt
     *                          创建 Unix 毫秒
     * @param stockMode
     *                          库存模式
     * @param startBucketNo
     *                          计划起始桶
     * @param layoutVersion
     *                          布局版本
     */
    public SeckillOrderMessage(String messageId, String requestId, Long userId, Long voucherId, long createdAt,
            String stockMode, Integer startBucketNo, Integer layoutVersion) {
        this(messageId, requestId, userId, voucherId, createdAt, stockMode, startBucketNo, layoutVersion, 1);
    }
}
