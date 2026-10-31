package com.voucherx.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求头读取 Token。支持 Authorization: Bearer &lt;token&gt; 与直接传 token。
 */
public final class TokenExtractor {

    private TokenExtractor() {
    }

    public static String extract(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }
}
