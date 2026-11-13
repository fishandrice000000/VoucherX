package com.voucherx.commerce.model;

public record PaymentEvent(String eventId, String orderKind, long orderId, long userId, long amount, boolean success,
        String outcome, String orderState, long createdAtMs) {
}
