package com.voucherx.admission;

import com.voucherx.common.redis.RedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 侧共享/活动双层令牌桶。两个桶在同一个 Lua 中先判断后扣减，避免活动桶
 * 成功而共享桶失败造成部分扣减。
 */
@Component
public class RedisAdmissionStore {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> acquireScript;

    public RedisAdmissionStore(StringRedisTemplate redisTemplate,
                               @Qualifier("admissionAcquireScript") DefaultRedisScript<Long> acquireScript) {
        this.redisTemplate = redisTemplate;
        this.acquireScript = acquireScript;
    }

    public AdmissionDecision tryAcquire(Long voucherId, AdmissionProperties properties) {
        AdmissionProperties.Global global = properties.global();
        try {
            Long result = redisTemplate.execute(
                    acquireScript,
                    List.of(RedisKeys.admissionSharedTokens(),
                            RedisKeys.admissionSharedPolicy(),
                            RedisKeys.admissionActivityTokens(voucherId),
                            RedisKeys.admissionActivityPolicy(voucherId)),
                    "1",
                    String.valueOf(global.sharedRate()),
                    String.valueOf(global.sharedCapacity()),
                    String.valueOf(global.activityRate()),
                    String.valueOf(global.activityCapacity()),
                    properties.mode().name()
            );
            if (result == null) {
                return AdmissionDecision.INVALID_POLICY;
            }
            return switch (result.intValue()) {
                case 1 -> AdmissionDecision.ALLOWED;
                case -1 -> AdmissionDecision.SHARED_REJECTED;
                case -2 -> AdmissionDecision.ACTIVITY_REJECTED;
                case -3 -> AdmissionDecision.POLICY_STALE;
                default -> AdmissionDecision.INVALID_POLICY;
            };
        } catch (DataAccessException ex) {
            throw new AdmissionUnavailableException("Redis 准入服务不可用", ex);
        }
    }

    public boolean isAvailable() {
        try {
            redisTemplate.hasKey(RedisKeys.admissionSharedPolicy());
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }
}
