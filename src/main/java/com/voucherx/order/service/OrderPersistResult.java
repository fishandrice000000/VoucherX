package com.voucherx.order.service;

/**
 * 建单事务主体的结果，区分新建与复用既有订单。
 * <p>
 * 在 persistBatch 内每项返回时仍可能未提交；只有相应最外层事务成功结束后，消费者才能据此 ACK。
 * created=true 也只表示购买单新建，不表示已付款或已发券。
 *
 * @param orderId
 *                    新建或复用的购买订单 ID
 * @param created
 *                    本次事务主体是否新插入了购买订单
 */
public record OrderPersistResult(Long orderId, boolean created) {
}
