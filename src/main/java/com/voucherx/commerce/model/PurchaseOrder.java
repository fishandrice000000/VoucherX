package com.voucherx.commerce.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 购买单的完整交易视图，与基础建单实体 VoucherOrder 读取同一张 tb_voucher_order。
 * <p>
 * 付款及权益字段在建单时固定，购买付款按这些快照生成用户券；JSON 使用 snake_case。
 * 整数 status 是基础建单状态，字符串 paymentStatus 才是当前付款状态。
 *
 * <p>
 * 新购买单由 initializePurchase 在建单事务内固定价格、权益和付款期限，并设置 PENDING_PAYMENT。
 *
 * @param id
 *                                  购买订单主键
 * @param requestId
 *                                  抢券请求 ID
 * @param messageId
 *                                  固定 Outbox 事件 ID
 * @param userId
 *                                  买券用户 ID
 * @param voucherId
 *                                  券模板/活动 ID
 * @param stockMode
 *                                  SINGLE 或 BUCKET
 * @param bucketNo
 *                                  MySQL 实际扣减成功的桶号；SINGLE 为 0
 * @param layoutVersion
 *                                  建单时的库存布局版本
 * @param status
 *                                  基础建单状态，创建成功为 1
 * @param completedAtMs
 *                                  插单前记录的近似落库 Unix 毫秒，不是精确提交时刻
 * @param createTime
 *                                  数据库创建时间
 * @param updateTime
 *                                  数据库更新时间
 * @param paymentStatus
 *                                  PENDING_PAYMENT、PAID、CANCELLED
 * @param payAmount
 *                                  买券应付价格，分，不是券面额
 * @param couponFaceValue
 *                                  付款后所发券的面额，分
 * @param couponShopId
 *                                  该券可用商户 ID
 * @param couponMinSpend
 *                                  券使用门槛，分
 * @param couponValiditySeconds
 *                                  成功付款后开始计算的券有效时长，秒
 * @param paymentDeadlineMs
 *                                  购买付款截止 Unix 毫秒
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseOrder(long id, String requestId, String messageId, long userId, long voucherId, String stockMode,
        int bucketNo, int layoutVersion, int status, long completedAtMs, java.time.LocalDateTime createTime,
        java.time.LocalDateTime updateTime, String paymentStatus, long payAmount, long couponFaceValue,
        long couponShopId,
        long couponMinSpend, long couponValiditySeconds, long paymentDeadlineMs) implements CommerceOrder {
}
