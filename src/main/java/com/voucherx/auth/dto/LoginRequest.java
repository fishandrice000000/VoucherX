package com.voucherx.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 验证码登录的输入；成功消费验证码后建立用户及 Token。
 *
 * @param phone
 *                  与验证码关联的手机号
 * @param code
 *                  六位数字验证码
 */
public record LoginRequest(
        @NotBlank(message = "不能为空") @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,

        @NotBlank(message = "不能为空") @Pattern(regexp = "^\\d{6}$", message = "验证码必须是 6 位数字") String code) {
}
