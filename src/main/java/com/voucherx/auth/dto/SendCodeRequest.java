package com.voucherx.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 申请验证码的输入，由 Bean Validation 校验手机号格式。
 *
 * @param phone
 *                  用户填写的手机号
 */
public record SendCodeRequest(
        @NotBlank(message = "不能为空") @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone) {
}
