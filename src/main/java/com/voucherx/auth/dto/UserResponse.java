package com.voucherx.auth.dto;

public record UserResponse(
        Long userId,
        String phone,
        String nickName) {
}
