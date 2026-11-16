package com.voucherx.fulfillment;

/**
 * 认领事件后读取的摘要，包含关联请求和累计尝试次数。
 * <p>
 * 调用者另外保存本次 claim_token，回写时以令牌及 CLAIMED 状态执行条件更新。
 *
 * @param requestId
 *                      事件关联的抢券请求 ID
 * @param attempts
 *                      已累计的成功认领次数，正常包含本轮，用于失败分支的重试预算判断
 */
public record OutboxClaim(String requestId, int attempts) {
}
