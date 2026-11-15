package com.voucherx.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求头读取 Token。仅接受 Authorization: Bearer &lt;token&gt;。
 */
public final class TokenExtractor {

    private TokenExtractor() {
    }

    /**
     * 只解析 Authorization 请求头，要求 Bearer 前缀和非空 Token；凭证有效性由拦截器检查。
     *
     * @param request
     *                    当前 HTTP 请求
     *
     * @return 去除前缀及首尾空格的 Token；格式不符或凭证为空时为 {@code null}
     */
    public static String extract(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = value.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
