package com.voucherx.auth.context;

/**
 * 当前请求的用户上下文。必须在请求结束后清理，避免线程复用造成身份串用。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        CURRENT_USER.set(userId);
    }

    public static Long getRequired() {
        Long userId = CURRENT_USER.get();
        if (userId == null) {
            throw new IllegalStateException("当前请求没有登录用户上下文");
        }
        return userId;
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
