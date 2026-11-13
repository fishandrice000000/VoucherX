package com.voucherx.recovery;

/**
 * 可被测试精确命中的业务执行位置；名称只标识位置，不代表提交已经完成。
 * <p>
 * 06 从 RECOVERY_FROZEN、RECOVERY_PLANNED、RECOVERY_PUBLISHED 读到
 * RECOVERY_BEFORE_RESUME，
 * 对照 RecoveryService 中故障点与事务、Redis 写入的相对位置，判断重入会从哪个阶段继续。
 *
 * @see FaultInjector
 * @see RecoveryService
 */
public enum FaultPoint {
    /**
     * Redis Lua 预留成功之后、数据库持久化受理之前，可能留下带 Redis 证据的 INTENT。
     */
    RESERVE_DONE,
    /**
     * 受理服务事务调用返回之后，HTTP 返回之前；无外层事务时受理与 Outbox 已提交。
     */
    ACCEPT_COMMITTED,
    /**
     * 已认领 Outbox，向 RabbitMQ 发布之前；失败后由租约恢复继续处理。
     */
    OUTBOX_BEFORE_SEND,
    /**
     * 发布尝试返回之后、投递结果写回之前；返回结果也可能是 UNKNOWN，并不保证收到肯定确认。
     */
    OUTBOX_AFTER_CONFIRM,
    /**
     * 单笔订单、库存及请求结果已写入当前事务，事务提交之前。
     */
    ORDER_BEFORE_COMMIT,
    /**
     * 单笔持久化服务返回之后、消费者 ACK 之前；重投仍需靠请求及订单幂等。
     */
    ORDER_AFTER_COMMIT_BEFORE_ACK,
    /**
     * 批量业务 SQL 执行后、批量事务提交之前。
     */
    BATCH_BEFORE_COMMIT,
    /**
     * 批量持久化服务返回之后、消费者逐条 ACK 之前。
     */
    BATCH_AFTER_COMMIT_BEFORE_ACK,
    /**
     * 消费下单锁券操作在事务内执行后；暂停仍持有所在事务的锁。
     */
    COUPON_LOCKED,
    /**
     * 付款状态和事件已写入当前事务，付款事务提交之前。
     */
    PAYMENT_BEFORE_COMMIT,
    /**
     * 关闭及补偿意图的 afterCommit 回调中；此处抛异常不能回滚已提交的关闭屏障。
     */
    COMPENSATION_INTENT_COMMITTED,
    /**
     * Redis 已执行释放、数据库补偿进度提交之前；重试不能重复返还。
     */
    COMPENSATION_AFTER_RELEASE,
    /**
     * 数据库冻结与 Redis 冻结同步的事务回调均返回之后；维护入口无外层事务时冻结已提交。
     */
    RECOVERY_FROZEN,
    /**
     * 计划阶段事务回调返回之后，进入 Redis 重建之前；无外层事务时 RECONCILING 已提交。
     */
    RECOVERY_PLANNED,
    /**
     * Redis 三组结构已发布，但仍在重建数据库事务内；异常可使数据库保留 RECONCILING。
     */
    RECOVERY_PUBLISHED,
    /**
     * 复流校验通过后、Redis 门禁开放之前；当前仍持有数据库活动独占锁。
     */
    RECOVERY_BEFORE_RESUME
}
