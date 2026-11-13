package com.voucherx.commerce.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UserCoupon(long id, long purchaseOrderId, long userId, long voucherId, long shopId, long faceValue,
        long minSpend, long validFromMs, long validUntilMs, String state, Long lockOrderId, String lockToken,
        long version) {
}
