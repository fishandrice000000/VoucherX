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

/**
 * 手机号验证码登录服务，Redis 保存验证码和会话，MySQL 保存用户。
 * <p>
 * 沿 sendCode、login 阅读发码与登录，再进入 AuthInterceptor.preHandle 查看 Token 如何还原用户身份。
 * 验证码原子消费、用户建档和登录态写入按顺序分别执行。
 */
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

    /**
     * 先检查 IP 发送次数和手机号冷却时间，再生成六位验证码写入 Redis，设置配置中的有效期。
     * <p>
     * 当前发送动作是模拟日志；普通日志只记录脱敏手机号，返回体是否暴露验证码由配置决定。
     *
     * @param phone
     *                     接口已校验格式的手机号
     * @param clientIp
     *                     HTTP 请求远端 IP；为空时跳过 IP 限流
     *
     * @return 手机号、有效秒数及可选调试验证码
     */
    public CodeResponse sendCode(String phone, String clientIp) {
        consumeIpSendQuota(clientIp);
        acquirePhoneSendCooldown(phone);

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redisTemplate.opsForValue().set(RedisKeys.authCode(phone), code, authProperties.codeTtl());
        // 模拟发送：不记录验证码，避免敏感信息进入普通日志。
        log.info("已向 {} 模拟发送验证码", maskPhone(phone));
        return new CodeResponse(phone, authProperties.codeTtlSeconds(),
                authProperties.exposeCode() ? code : null);
    }

    /**
     * 用 Lua 原子地“比较并删除”验证码，通过后查询或创建用户，生成随机 Token。
     * <p>
     * Redis 保存 Token 到用户 ID 的映射并设置有效期；Token 是随机字符串。
     * 若消费验证码之后建档或写登录态失败，已消费的验证码不会自动恢复。
     *
     * @param phone
     *                  登录手机号
     * @param code
     *                  客户端提交的验证码
     *
     * @return 随机 Token、用户 ID 和手机号
     */
    public LoginResponse login(String phone, String code) {
        Long consumed = redisTemplate.execute(
                consumeAuthCodeScript,
                List.of(RedisKeys.authCode(phone)),
                code);
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
                authProperties.tokenTtl());
        return new LoginResponse(token, user.getId(), user.getPhone());
    }

    /**
     * 使指定 Token 失效；重复调用或传入空值不会删除用户资料。
     *
     * @param token
     *                  从 Authorization 请求头提取的登录凭证
     */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(RedisKeys.token(token));
        }
    }

    /**
     * 按登录上下文中的用户 ID 读取 MySQL；资料不存在时按登录失效处理。
     *
     * @param userId
     *                   拦截器确认的用户 ID
     *
     * @return 用户 ID、手机号和昵称
     */
    public UserResponse currentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或登录态失效");
        }
        return new UserResponse(user.getId(), user.getPhone(), user.getNickName());
    }

    /**
     * 对 IP 执行 Redis 计数，首次计数设置一分钟有效期；超过配置上限时拒绝。
     *
     * @param clientIp
     *                     请求远端 IP；空值不计数
     */
    private void consumeIpSendQuota(String clientIp) {
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

    /**
     * 用带过期时间的 SET NX 抢占手机号冷却标记，避免短时间重复发码。
     *
     * @param phone
     *                  申请验证码的手机号
     */
    private void acquirePhoneSendCooldown(String phone) {
        String key = RedisKeys.authCodeCooldown(phone);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key, "1", Duration.ofSeconds(authProperties.codeCooldownSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(ErrorCode.AUTH_CODE_TOO_FREQUENT);
        }
    }

    /**
     * 通过 MyBatis-Plus 查询手机号对应的用户；手机号唯一约束负责数据库层去重。
     *
     * @param phone
     *                  登录手机号
     *
     * @return 用户；尚未注册时为 {@code null}
     */
    private User findByPhone(String phone) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, phone).last("limit 1"));
    }

    /**
     * 首次登录自动建档；并发插入命中手机号唯一约束时重新查询，复用已创建的用户。
     *
     * @param phone
     *                  已通过验证码校验的手机号
     *
     * @return 新建用户或并发请求已建立的用户
     */
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

    /**
     * 将手机号中间部分替换为星号，供模拟发送日志使用。
     *
     * @param phone
     *                  原始手机号
     *
     * @return 脱敏文本；过短或为空时返回星号
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
