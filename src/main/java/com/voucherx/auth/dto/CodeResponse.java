package com.voucherx.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 开发环境可以返回模拟验证码；生产环境 code 为 null 且不会序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeResponse(
        String phone,
        long expiresInSeconds,
        String code) {
}
