package com.voucherx.mq.publisher;

/**
 * 单次 RabbitMQ 发布的确认结果，不表示消费、建单或付款结果。
 * <p>
 * 发布失败分类不能单独授权回补库存；释放还须满足持久化关闭和归属核验。
 */
public enum PublishResult {
    /** Broker 确认接收且消息成功路由到队列。 */
    CONFIRMED,
    /** Broker 确认接收，但 mandatory 消息无法路由。消息不会进入队列。 */
    RETURNED,
    /** Broker 明确 NACK。 */
    NACKED,
    /** 连接阶段即失败，可以确定消息未发送。 */
    NOT_SENT,
    /** 确认超时、连接中断等，消息是否进入队列无法确定。 */
    UNKNOWN;

    /**
     * 判断本次发布结果是否归类为明确未成功入队；即使返回 true，仍不能代替数据库关闭屏障直接释放预留。
     *
     * @return RETURNED、NACKED 或 NOT_SENT 时为 true
     */
    public boolean definitelyNotQueued() {
        return this == RETURNED || this == NACKED || this == NOT_SENT;
    }
}
