package com.voucherx.mq.publisher;

/**
 * 单次 RabbitMQ 发布尝试的确认分类，供 Outbox 安排发送进度。
 * <p>
 * 请求建单结果由账本事务推进，预留释放由持久化关闭和归属核验后的补偿协议执行。
 */
public enum PublishResult {
    /**
     * Broker Confirm 为 ACK 且未观察到不可路由退回，发送器可将原事件记为 SENT。
     */
    CONFIRMED,
    /**
     * mandatory 路由检查退回本次消息，由 Outbox 按投递预算安排重试或隔离。
     */
    RETURNED,
    /**
     * Broker 对本次发布明确 NACK；发送器仍按持久化事件安排重试或隔离。
     */
    NACKED,
    /**
     * 发布器捕获连接阶段失败，将该次发布分类为未发送；原业务请求仍保留。
     */
    NOT_SENT,
    /**
     * 等待确认超时、中断或某些发送异常，无法确定本次消息是否入队，重试可能产生重复交付。
     */
    UNKNOWN;

}
