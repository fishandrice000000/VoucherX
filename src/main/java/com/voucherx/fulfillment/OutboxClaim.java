package com.voucherx.fulfillment;

/**
 * 事件被认领后的查询摘要；令牌竞争及资格校验仍由 Outbox 条件更新负责。
 *
 * @param requestId
 *                      事件关联的抢券请求 ID
 * @param attempts
 *                      已发生的认领尝试次数，包含本次
 */
public record OutboxClaim(String requestId, int attempts) {
}
