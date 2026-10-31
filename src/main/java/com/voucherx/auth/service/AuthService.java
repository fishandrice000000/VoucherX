package com.voucherx.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.voucherx.auth.dto.CodeResponse;
import com.voucherx.auth.dto.LoginResponse;
import com.voucherx.auth.dto.UserResponse;
import com.voucherx.auth.entity.User;
import com.voucherx.auth.mapper.UserMapper;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final AuthProperties authProperties;
    private final DefaultRedisScript<Long> consumeAuthCodeScript;

    public AuthService(UserMapper userMapper,
                       StringRedisTemplate redisTemplate,
                       AuthProperties authProperties,
                       @Qualifier("consumeAuthCodeScript") DefaultRedisScript<Long> consumeAuthCodeScript) {
        this.userMapper = userMapper;
        this.redisTemplate = redisTemplate;
        this.authProperties = authProperties;
        this.consumeAuthCodeScript = consumeAuthCodeScript;
    }

    public CodeResponse sendCode(String phone, String clientIp) {
        checkIpLimit(clientIp);
        checkPhoneCooldown(phone);

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redisTemplate.opsForValue().set(RedisKeys.authCode(phone), code, authProperties.codeTtl());
        // 模拟发送：不记录验证码，避免敏感信息进入普通日志。
        log.info("已向 {} 模拟发送验证码", maskPhone(phone));
        return new CodeResponse(phone, authProperties.codeTtlSeconds(),
                authProperties.exposeCode() ? code : null);
    }

    public LoginResponse login(String phone, String code) {
        Long consumed = redisTemplate.execute(
                consumeAuthCodeScript,
                List.of(RedisKeys.authCode(phone)),
                code
        );
        if (consumed == null || consumed != 1L) {
            throw new BusinessException(ErrorCode.AUTH_CODE_INVALID);
        }

        User user = findByPhone(phone);
        if (user == null) {
            user = createUser(phone);
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                RedisKeys.token(token),
                String.valueOf(user.getId()),
                authProperties.tokenTtl()
        );
        return new LoginResponse(token, user.getId(), user.getPhone());
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(RedisKeys.token(token));
        }
    }

    public UserResponse currentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或登录态失效");
        }
        return new UserResponse(user.getId(), user.getPhone(), user.getNickName());
    }

    private void checkIpLimit(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        String key = RedisKeys.authCodeIpLimit(clientIp);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(1));
        }
        if (count != null && count > authProperties.ipLimitPerMinute()) {
            throw new BusinessException(ErrorCode.AUTH_CODE_TOO_FREQUENT);
        }
    }

    private void checkPhoneCooldown(String phone) {
        String key = RedisKeys.authCodeCooldown(phone);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key, "1", Duration.ofSeconds(authProperties.codeCooldownSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(ErrorCode.AUTH_CODE_TOO_FREQUENT);
        }
    }

    private User findByPhone(String phone) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, phone).last("limit 1"));
    }

    private User createUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("用户" + phone.substring(phone.length() - 4));
        user.setIcon("");
        try {
            userMapper.insert(user);
            return user;
        } catch (DuplicateKeyException ex) {
            // 并发首次登录时可能同时创建，最终以数据库唯一约束为准。
            User existing = findByPhone(phone);
            if (existing == null) {
                throw ex;
            }
            return existing;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
