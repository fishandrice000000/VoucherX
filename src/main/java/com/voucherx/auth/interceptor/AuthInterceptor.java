package com.voucherx.auth.interceptor;

import com.voucherx.auth.context.UserContext;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 在受保护的 HTTP 请求进入 Controller 前，校验 Redis 登录态并续期。
 * <p>
 * 成功后将用户 ID 放入 {@link com.voucherx.auth.context.UserContext}，请求结束后清理。
 * 这里只认证身份；订单、请求和券的归属仍由各业务服务检查。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String USER_ID_ATTRIBUTE = "voucherx.userId";

    private final StringRedisTemplate redisTemplate;
    private final AuthProperties authProperties;

    public AuthInterceptor(StringRedisTemplate redisTemplate, AuthProperties authProperties) {
        this.redisTemplate = redisTemplate;
        this.authProperties = authProperties;
    }

    /**
     * 提取 Token，读取 Redis 中的用户 ID，续期后写入请求属性和当前线程上下文。
     * <p>
     * 没有有效 Token 时拒绝访问；Redis 连接失败时返回服务暂不可用，Controller 不会继续执行。
     *
     * @param request
     *                     当前 HTTP 请求
     * @param response
     *                     当前 HTTP 响应
     * @param handler
     *                     Spring MVC 选中的处理器
     *
     * @return 认证通过时为 {@code true}
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = TokenExtractor.extract(request);
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        String tokenKey = RedisKeys.token(token);
        String userIdValue;
        try {
            userIdValue = redisTemplate.opsForValue().get(tokenKey);
            if (userIdValue == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            // 有效请求刷新登录态；不把 Token 写入日志。
            redisTemplate.expire(tokenKey, authProperties.tokenTtl().toSeconds(), TimeUnit.SECONDS);
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "登录态服务暂不可用", ex);
        }

        Long userId;
        try {
            userId = Long.valueOf(userIdValue);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录态无效");
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        UserContext.set(userId);
        return true;
    }

    /**
     * 请求处理结束后移除 ThreadLocal 身份，避免线程池复用时把上一位用户的身份带给下一次请求。
     *
     * @param request
     *                     已完成的请求
     * @param response
     *                     HTTP 响应
     * @param handler
     *                     本次处理器
     * @param ex
     *                     处理期间的异常；正常结束时可能为空
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        UserContext.clear();
    }

}
