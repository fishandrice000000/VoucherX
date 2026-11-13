package com.voucherx.order.service;

/**
 * 携带明确业务拒绝原因的建单异常，抛出后由事务代理回滚本次 MySQL 写入。
 * <p>
 * 消费者按 failure 分类核验并关闭请求；暂时性数据库或消息异常走另一条重试路径。
 * 业务拒绝本身不会自动返还 Redis 预留，关闭与释放需按补偿协议处理。
 *
 * @see OrderFailure
 * @see com.voucherx.order.service.OrderPersistenceService
 */
public class OrderBusinessException extends RuntimeException {

    private final OrderFailure failure;

    /**
     * 把业务分类与诊断文本一起传回消费者；不在异常构造过程中执行关闭或补偿。
     *
     * @param failure
     *                    已判定的业务拒绝分类
     * @param message
     *                    便于排查的原因文本
     */
    public OrderBusinessException(OrderFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    /**
     * 读取业务拒绝分类，消费者据此记录失败原因。
     *
     * @return 构造时提供的 OrderFailure
     */
    public OrderFailure failure() {
        return failure;
    }
}
