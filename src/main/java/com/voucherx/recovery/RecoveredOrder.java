package com.voucherx.recovery;

public record RecoveredOrder(long id, long userId, String requestId, String messageId) {
}
