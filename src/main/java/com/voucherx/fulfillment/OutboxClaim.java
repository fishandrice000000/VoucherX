package com.voucherx.fulfillment;

public record OutboxClaim(String requestId, int attempts) {
}
