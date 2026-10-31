package com.voucherx.order.service;

public enum OrderFailure {
    VOUCHER_NOT_FOUND,
    SOLD_OUT,
    LOCAL_SOLD_OUT,
    DUPLICATE_USER,
    LAYOUT_MISMATCH
}
