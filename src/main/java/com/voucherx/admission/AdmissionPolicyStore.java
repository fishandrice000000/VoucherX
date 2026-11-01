package com.voucherx.admission;

import com.voucherx.common.redis.RedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 共享准入策略写入。控制器每次发布都先按旧速率结算，再设置新速率/容量和有效期。
 */
@Component
public class AdmissionPolicyStore {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> updateScript;

    public AdmissionPolicyStore(StringRedisTemplate redisTemplate,
                                @Qualifier("admissionPolicyUpdateScript")
                                DefaultRedisScript<Long> updateScript) {
        this.redisTemplate = redisTemplate;
        this.updateScript = updateScript;
    }

    public boolean updateShared(double rate, double capacity, long validUntilMillis) {
        try {
            Long result = redisTemplate.execute(
                    updateScript,
                    List.of(RedisKeys.admissionSharedTokens(),
                            RedisKeys.admissionSharedPolicy()),
                    String.valueOf(rate),
                    String.valueOf(capacity),
                    String.valueOf(validUntilMillis)
            );
            return result != null && result == 1L;
        } catch (DataAccessException ex) {
            return false;
        }
    }
}
