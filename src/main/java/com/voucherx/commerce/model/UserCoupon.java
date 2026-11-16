package com.voucherx.commerce.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 购买付款成功后发给用户的优惠券实例，映射 tb_user_coupon。
 * <p>
 * 使用流程为 AVAILABLE → LOCKED → USED；消费取消时释放为 AVAILABLE 或 EXPIRED。
 * JSON 使用 snake_case，例如 Java faceValue() 对应 face_value。
 * <p>
 * lockToken 标识一次消费锁归属。核销后可保留归属字段用于追溯，券当前可用性由 state 和有效期决定；释放时清空归属。
 *
 * @param id
 *                            用户券 ID，例如指南中的 4001
 * @param purchaseOrderId
 *                            发放这张券的购买订单 ID，唯一约束防止重复发放
 * @param userId
 *                            券拥有者用户 ID
 * @param voucherId
 *                            来源模板/活动 ID
 * @param shopId
 *                            允许使用的商户 ID
 * @param faceValue
 *                            面额，分
 * @param minSpend
 *                            满减使用门槛，分
 * @param validFromMs
 *                            生效 Unix 毫秒，包含该时刻
 * @param validUntilMs
 *                            失效 Unix 毫秒，不包含该时刻
 * @param state
 *                            AVAILABLE、LOCKED、USED 或 EXPIRED
 * @param lockOrderId
 *                            锁住该券的消费订单 ID；首次发放或释放后为空，核销后可保留
 * @param lockToken
 *                            本轮券锁随机令牌；首次发放或释放后为空，核销后可保留归属信息
 * @param version
 *                            并发条件更新使用的版本号，锁定/核销/释放时递增
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UserCoupon(long id, long purchaseOrderId, long userId, long voucherId, long shopId, long faceValue,
        long minSpend, long validFromMs, long validUntilMs, String state, Long lockOrderId, String lockToken,
        long version) {
}
