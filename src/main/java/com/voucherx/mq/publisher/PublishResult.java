package com.voucherx.mq.publisher;

/**
 * 单次 RabbitMQ 发布的确认结果，不表示消费、建单或付款结果。
 * <p>
 * 发布失败分类不能单独授权回补库存；释放还须满足持久化关闭和归属核验。
 */
public enum PublishResult {
    /**
     * 本次 Confirm 为 ACK 且未观察到不可路由退回，发送器可尝试记 SENT；不是消费者 ACK 或订单成功。
     */
    CONFIRMED,
    /**
     * 本次消息被 mandatory 路由检查退回，不能据此撤销已有受理或直接返库存。
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
