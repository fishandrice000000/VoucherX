package com.voucherx.recovery;

/**
 * 恢复扫描使用的已发行订单最小视图，用于重建资格、归属及成功请求状态。
 * <p>
 * 来自 tb_voucher_order，扫描不按付款或核销状态筛选；这是已有订单的投影，不会在恢复时新建订单。
 *
 * @param id
 *                      订单 ID，同时作为升序分页游标
 * @param userId
 *                      已占用活动资格的用户 ID
 * @param requestId
 *                      原请求 ID，用作 owners 归属和请求 Hash 键
 * @param messageId
 *                      原消息 ID，写回成功请求快照
 */
public record RecoveredOrder(long id, long userId, String requestId, String messageId) {
}
