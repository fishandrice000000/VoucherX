package com.voucherx.commerce.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseOrder(long id, String requestId, String messageId, long userId, long voucherId, String stockMode,
        int bucketNo, int layoutVersion, int status, long completedAtMs, java.time.LocalDateTime createTime,
        java.time.LocalDateTime updateTime, String paymentStatus, long payAmount, long couponFaceValue,
        long couponShopId,
        long couponMinSpend, long couponValiditySeconds, long paymentDeadlineMs) implements CommerceOrder {
}
