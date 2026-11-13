package com.voucherx.commerce.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 付款买券后实际发给用户的券，映射 tb_user_coupon；与活动/模板是不同对象。
 * <p>
 * 正常使用为 AVAILABLE → LOCKED → USED；取消消费单则释放为 AVAILABLE 或 EXPIRED。
 * JSON 使用 snake_case，Java 使用 record 访问器，例如 faceValue() 对应 JSON 的 face_value。
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
