package com.voucherx.commerce.model;

/**
 * 支付事件的输入及首次处理回执快照，映射支付事件表。
 * <p>
 * success 保存支付声明，outcome 保存处理分类，orderState 保存该次处理完成时的订单状态。
 * 交易服务持订单锁核对归属和金额，重放同一事件返回此记录中的快照。
 *
 * @param eventId
 *                        全局唯一支付事件幂等键
 * @param orderKind
 *                        PURCHASE 或 CONSUMPTION
 * @param orderId
 *                        对应类型的订单 ID
 * @param userId
 *                        付款用户 ID
 * @param amount
 *                        声明支付金额，分
 * @param success
 *                        事件输入的支付成功声明，不等于权益已实际应用
 * @param outcome
 *                        实际处理结果，例如 APPLIED 或 FAILED_ATTEMPT
 * @param orderState
 *                        此事件处理后的订单付款状态
 * @param createdAtMs
 *                        事件处理 Unix 毫秒
 */
public record PaymentEvent(String eventId, String orderKind, long orderId, long userId, long amount, boolean success,
        String outcome, String orderState, long createdAtMs) {
}
