package com.voucherx.auth.context;

/**
 * 把“当前 HTTP 请求是谁”保存在当前线程中，供 Controller 读取。
 * <p>
 * 由认证拦截器设置和清理。ThreadLocal 不会自动跨线程传给定时任务或 MQ 消费者，
 * 因此异步下单必须使用消息中的 userId。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 认证通过后绑定当前线程的用户身份。
     *
     * @param userId
     *                   Redis 登录态中读出的用户 ID
     */
    public static void set(Long userId) {
        CURRENT_USER.set(userId);
    }

    /**
     * 读取当前请求的用户；缺失身份表示受保护链路的调用前提未满足。
     *
     * @throws IllegalStateException
     *                                   当前线程没有绑定用户
     *
     * @return 当前登录用户 ID
     */
    public static Long getRequired() {
        Long userId = CURRENT_USER.get();
        if (userId == null) {
            throw new IllegalStateException("当前请求没有登录用户上下文");
        }
        return userId;
    }

    /**
     * 移除当前线程保存的身份；在请求结束时执行，防止线程复用造成身份串用。
     */
    public static void clear() {
        CURRENT_USER.remove();
    }
}
