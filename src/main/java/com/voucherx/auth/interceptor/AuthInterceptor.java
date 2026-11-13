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
 * 登录拦截器：只负责 Token 校验与登录态续期，不承担业务授权。
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

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        UserContext.clear();
    }

}
