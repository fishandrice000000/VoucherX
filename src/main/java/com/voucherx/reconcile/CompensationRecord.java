package com.voucherx.reconcile;

public record CompensationRecord(String compensationId, String state, int attempts, long nextAtMs, String reason) {
}
