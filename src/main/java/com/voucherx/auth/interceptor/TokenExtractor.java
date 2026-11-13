package com.voucherx.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求头读取 Token。支持 Authorization: Bearer &lt;token&gt; 与直接传 token。
 */
public final class TokenExtractor {

    private TokenExtractor() {
    }

    /**
     * 只解析 Authorization 请求头，兼容 Bearer 前缀和直接传入 Token；凭证有效性由拦截器检查。
     *
     * @param request
     *                    当前 HTTP 请求
     *
     * @return 去除前缀及首尾空格的 Token；请求头缺失或空白时为 {@code null}
     */
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
