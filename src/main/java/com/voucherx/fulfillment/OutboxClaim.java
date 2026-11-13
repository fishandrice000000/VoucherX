package com.voucherx.fulfillment;

/**
 * 认领后按事件 ID 读取的摘要，包含关联请求及当前累计尝试次数。
 * <p>
 * 它不包含 claim_token，也不证明调用者此刻仍持有认领权；回写必须另带本轮令牌执行条件更新。
 *
 * @param requestId
 *                      事件关联的抢券请求 ID
 * @param attempts
 *                      已累计的成功认领次数，正常包含本轮，用于失败分支的重试预算判断
 */
public record OutboxClaim(String requestId, int attempts) {
}
