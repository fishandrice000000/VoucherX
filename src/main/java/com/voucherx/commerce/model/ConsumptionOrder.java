package com.voucherx.commerce.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConsumptionOrder(long id, long userId, long shopId, long couponId, String lockToken, long originalAmount,
        long discountAmount, long payAmount, String paymentStatus, long paymentDeadlineMs, long createdAtMs)
        implements CommerceOrder {
}
