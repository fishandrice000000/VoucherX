package com.voucherx.commerce.model;

public record VoucherTerms(long payValue, long actualValue, long shopId, long minSpend, long validitySeconds) {
}
