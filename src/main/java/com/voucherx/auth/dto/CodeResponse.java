package com.voucherx.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 验证码申请结果；只有开启 expose-code 配置时包含 code，空值不序列化。
 *
 * @param phone
 *                             接收验证码的手机号
 * @param expiresInSeconds
 *                             验证码有效时长，秒
 * @param code
 *                             调试验证码；默认不暴露，为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeResponse(
        String phone,
        long expiresInSeconds,
        String code) {
}
