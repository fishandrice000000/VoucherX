package com.voucherx.order.service;

/**
 * 异步建单的业务拒绝分类，通过 OrderBusinessException 交给消费者。
 * <p>
 * 消费者核对请求身份后处理关闭；LOCAL_SOLD_OUT 表示有界候选桶耗尽，SOLD_OUT 表示全活动余量为零。
 *
 * @see OrderBusinessException
 * @see com.voucherx.mq.consumer.SeckillOrderConsumer
 */
public enum OrderFailure {
    /**
     * 找不到请求账本，或消息中的业务身份、代次、布局与该请求不匹配。
     */
    INVALID_REQUEST,
    /**
     * 请求已经关闭，迟到投递不能继续建单。
     */
    REQUEST_CLOSED,
    /**
     * 建单时找不到对应秒杀活动。
     */
    VOUCHER_NOT_FOUND,
    /**
     * 单库存条件更新未命中，或桶汇总查询观察到该布局余额为 0。
     */
    SOLD_OUT,
    /**
     * 有界候选桶都未扣中，但汇总仍有余额；当前实现不会无界继续搜索其他桶。
     */
    LOCAL_SOLD_OUT,
    /**
     * 数据库已经存在同用户、同活动的购买订单。
     */
    DUPLICATE_USER,
    /**
     * 消息模式/版本、桶数量、编号范围或布局覆盖与活动不匹配。
     */
    LAYOUT_MISMATCH
}
