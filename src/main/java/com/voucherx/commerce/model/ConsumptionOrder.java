package com.voucherx.commerce.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 消费单快照，映射 tb_consumption_order；消费下单时创建，付款成功才核销用户券。
 * <p>
 * 例如原价 10000 分、优惠 2000 分，应付 8000 分；JSON 字段按 snake_case 输出。
 *
 * @param id
 *                              消费订单主键；插入前草稿用 0，自增结果另回填 GeneratedId
 * @param userId
 *                              下单用户 ID
 * @param shopId
 *                              消费商户 ID
 * @param couponId
 *                              本次使用的用户券 ID
 * @param lockToken
 *                              本轮锁券随机令牌，防止旧订单误用新锁
 * @param originalAmount
 *                              消费原价，分
 * @param discountAmount
 *                              本次实际优惠额，分
 * @param payAmount
 *                              应付金额，分，为原价减优惠
 * @param paymentStatus
 *                              PENDING_PAYMENT、PAID 或 CANCELLED
 * @param paymentDeadlineMs
 *                              付款截止 Unix 毫秒，不晚于用户券失效时刻
 * @param createdAtMs
 *                              消费单创建 Unix 毫秒
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConsumptionOrder(long id, long userId, long shopId, long couponId, String lockToken, long originalAmount,
        long discountAmount, long payAmount, String paymentStatus, long paymentDeadlineMs, long createdAtMs)
        implements CommerceOrder {
}
