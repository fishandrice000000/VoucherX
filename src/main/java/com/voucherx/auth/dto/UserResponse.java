package com.voucherx.auth.dto;

/**
 * 当前登录用户的公开资料。
 *
 * @param userId
 *                     MySQL 用户主键
 * @param phone
 *                     用户手机号
 * @param nickName
 *                     展示昵称
 */
public record UserResponse(
        Long userId,
        String phone,
        String nickName) {
}
