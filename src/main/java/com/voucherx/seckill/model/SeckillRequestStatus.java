package com.voucherx.seckill.model;

public enum SeckillRequestStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    UNKNOWN,
    RELEASED;

    public static SeckillRequestStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return SeckillRequestStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
