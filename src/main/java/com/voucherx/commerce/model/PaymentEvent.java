package com.voucherx.commerce.model;

/**
 * 支付事件及已判定的回执，映射支付事件表；重放同一事件返回这里保存的快照。
 *
 * <p>
 * success 是输入声明，outcome 才描述是否应用权益；orderState
 * 是历史回执快照。服务仍先锁订单做归属和金额校验，命中旧事件后返回原结果，不按当前状态重算回执。
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
