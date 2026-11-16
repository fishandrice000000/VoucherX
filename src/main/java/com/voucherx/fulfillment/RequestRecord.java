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
 *                          固定业务事件 ID，账本与消息保持一致，重发时保持不变
 * @param userId
 *                          抢券用户 ID
 * @param voucherId
 *                          活动 ID
 * @param phase
 *                          INTENT、ACCEPTED、SUCCEEDED 或 CLOSED
 * @param reservation
 *                          UNKNOWN、RESERVED、NONE 或 RELEASED 等预留状态
 * @param epoch
 *                          请求当前绑定的恢复代次；初次取自意图创建时，受控恢复可更新
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
     * 根据账本快照构造保持同一业务身份的待发布订单消息。
     * <p>
     * 重发保持 requestId、eventId、用户、活动和布局身份；createdAt 使用意图创建时刻，发布尝试由发送器另行标识。
     *
     * @return 等待发布器序列化为 JSON 的订单消息
     * @see com.voucherx.seckill.model.SeckillOrderMessage
     * @see com.voucherx.mq.publisher.SeckillMessagePublisher#publish
     */
    public SeckillOrderMessage message() {
        return new SeckillOrderMessage(eventId, requestId, userId,
                voucherId, createdAt, stockMode, startBucketNo, layoutVersion, epoch);
    }

    /**
     * 比较请求、事件、用户、活动、代次、模式、起始桶和布局版本的完整业务身份。
     * <p>
     * 调用者在需要时持锁，并结合活动状态与请求阶段判断本次执行资格。
     *
     * @param m
     *              必需身份已通过入口检查的非空消息
     * @return 上述持久化身份字段全部一致时为 true
     */
    public boolean matches(SeckillOrderMessage m) {
        return requestId.equals(m.requestId()) && eventId.equals(m.eventId()) && userId == m.userId()
                && voucherId == m.voucherId() && epoch == m.epoch() && stockMode.equals(m.stockMode())
                && Integer.valueOf(startBucketNo).equals(m.startBucketNo())
                && Integer.valueOf(layoutVersion).equals(m.layoutVersion());
    }
}
