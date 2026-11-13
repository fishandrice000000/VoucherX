package com.voucherx.auth.dto;

/**
 * 登录结果；客户端保存 token，并在后续请求 Authorization 头中携带。
 *
 * @param token
 *                   随机登录 Token，Redis 保存其用户映射
 * @param userId
 *                   MySQL 用户主键
 * @param phone
 *                   登录手机号
 */
public record LoginResponse(
        String token,
        Long userId,
        String phone) {
}
