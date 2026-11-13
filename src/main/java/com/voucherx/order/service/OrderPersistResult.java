package com.voucherx.order.service;

/**
 * 建单事务的返回值；重复消费可返回同一订单但 created 为 false。
 *
 * @param orderId
 *                    新建或复用的购买订单 ID
 * @param created
 *                    是否在本次事务中新建订单
 */
public record OrderPersistResult(Long orderId, boolean created) {
}
