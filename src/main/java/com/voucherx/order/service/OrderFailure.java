package com.voucherx.order.service;

public enum OrderFailure {
    INVALID_REQUEST,
    REQUEST_CLOSED,
    VOUCHER_NOT_FOUND,
    SOLD_OUT,
    LOCAL_SOLD_OUT,
    DUPLICATE_USER,
    LAYOUT_MISMATCH
}
