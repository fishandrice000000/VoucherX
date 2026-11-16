package com.voucherx.order.service;

/**
 * 建单事务的结果，包含订单 ID 和是否首次创建。
 * <p>
 * created=false 表示复用同请求已有订单；created=true 表示本事务创建购买订单。
 * 消费者在最外层事务成功提交后使用该结果更新快照并 ACK；购买订单付款由交易流程继续处理。
 *
 * @param orderId
 *                    新建或复用的购买订单 ID
 * @param created
 *                    本次事务主体是否新插入了购买订单
 */
public record OrderPersistResult(Long orderId, boolean created) {
}
