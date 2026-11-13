package com.voucherx.fulfillment;

import com.voucherx.seckill.model.SeckillOrderMessage;

/**
 * 一条抢券请求的持久化快照，映射 tb_seckill_request。
 * <p>
 * phase 回答业务推进到哪一步，reservation 回答 Redis 预留的核对状态，两者含义不同。
 * 例如 INTENT/UNKNOWN 只表示意图已保存，ACCEPTED/RESERVED 表示可靠受理，SUCCEEDED 表示订单已创建。
 *
 * @param requestId
 *                          抢券请求 ID，用于业务幂等和客户端查询
 * @param eventId
 *                          固定事件 ID；重发时保持不变，对应消息的 messageId
 * @param userId
 *                          抢券用户 ID
 * @param voucherId
 *                          活动 ID
 * @param phase
 *                          INTENT、ACCEPTED、SUCCEEDED 或 CLOSED
 * @param reservation
 *                          UNKNOWN、RESERVED、NONE 或 RELEASED 等预留状态
 * @param epoch
 *                          创建意图时的活动恢复代次
 * @param stockMode
 *                          SINGLE 或 BUCKET
 * @param startBucketNo
 *                          计划起始桶，不一定是最终实际扣减桶
 * @param layoutVersion
 *                          库存布局版本
 * @param orderId
 *                          建单成功后的购买订单 ID，尚未建单时为空
 * @param reason
 *                          最近记录的拒绝或诊断原因，可为空
 * @param createdAt
 *                          意图创建时刻，Unix 毫秒
 * @param updatedAt
 *                          最近业务更新时刻，Unix 毫秒
 */
public record RequestRecord(String requestId, String eventId, long userId, long voucherId,
        String phase, String reservation, long epoch, String stockMode, int startBucketNo,
        int layoutVersion, Long orderId, String reason, long createdAt, long updatedAt) {
    /**
     * 判断建单阶段是否已成功或已关闭，不用于判断订单是否付款。
     *
     * @return SUCCEEDED 或 CLOSED 时为 true
     */
    public boolean terminal() {
        return "SUCCEEDED".equals(phase) || "CLOSED".equals(phase);
    }

    /**
     * 从持久化身份和布局构造 MQ 消息；重发保持同一 requestId 与 eventId，避免创建新的业务请求。
     *
     * @return 供消息发布器序列化的订单消息
     */
    public SeckillOrderMessage message() {
        return new SeckillOrderMessage(eventId, requestId, userId,
                voucherId, createdAt, stockMode, startBucketNo, layoutVersion, epoch);
    }

    /**
     * 逐项比对业务身份、恢复代次及布局，防止伪造或过期投递作用到其他请求。
     *
     * @param m
     *              待校验的非空业务消息
     *
     * @return 所有要求的字段一致时为 true
     */
    public boolean matches(SeckillOrderMessage m) {
        return requestId.equals(m.requestId()) && eventId.equals(m.messageId()) && userId == m.userId()
                && voucherId == m.voucherId() && epoch == m.epoch() && stockMode.equals(m.stockMode())
                && Integer.valueOf(startBucketNo).equals(m.startBucketNo())
                && Integer.valueOf(layoutVersion).equals(m.layoutVersion());
    }
}
