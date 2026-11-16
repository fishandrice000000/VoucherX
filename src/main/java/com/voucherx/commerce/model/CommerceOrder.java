package com.voucherx.commerce.model;

/**
 * 购买单和消费单共有的付款视图；sealed 限定实现为这两种类型，调用方仍须同时携带订单类型定位。
 */
public sealed interface CommerceOrder permits PurchaseOrderSnapshot, ConsumptionOrder {
    /**
     * 返回该类型订单的数据库主键。
     *
     * @return 订单 ID；跨订单类型不保证唯一
     */
    long id();

    /**
     * 返回订单拥有者，供服务层做归属校验。
     *
     * @return 用户 ID
     */
    long userId();

    /**
     * 读取付款状态，与请求 SUCCESS 和基础建单 status=1 分开。
     *
     * @return PENDING_PAYMENT、PAID、CANCELLED
     */
    String paymentStatus();

    /**
     * 返回本订单实际应付金额。
     *
     * @return 金额，单位分
     */
    long payAmount();

    /**
     * 返回接受成功付款的截止时刻；到达该时刻即视为超时。
     *
     * @return Unix 毫秒
     */
    long paymentDeadlineMs();
}
