package com.voucherx.mq.publisher;

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

    public boolean definitelyNotQueued() {
        return this == RETURNED || this == NACKED || this == NOT_SENT;
    }
}
