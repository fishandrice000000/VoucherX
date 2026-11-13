package com.voucherx.auth.dto;

public record LoginResponse(
        String token,
        Long userId,
        String phone) {
}
