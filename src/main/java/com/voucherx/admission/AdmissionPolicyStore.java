package com.voucherx.admission;

import com.voucherx.common.redis.RedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 反馈侧的共享策略写入适配层，只操作共享令牌和策略 Hash，不改每个活动的固定配额。
 * <p>
 * 更新脚本先按旧速率结算，再设置新速率、容量和 validUntil；已有令牌不会因每次刷新策略而重置成满桶。
 *
 * @see AdaptiveAdmissionController
 * @see RedisAdmissionStore
 */
@Component
public class AdmissionPolicyStore {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> updateScript;

    public AdmissionPolicyStore(StringRedisTemplate redisTemplate,
            @Qualifier("admissionPolicyUpdateScript") DefaultRedisScript<Long> updateScript) {
        this.redisTemplate = redisTemplate;
        this.updateScript = updateScript;
    }

    /**
     * 将新共享速率、突发容量及绝对有效截止时刻交给 admission_policy_update.lua。
     * <p>
     * 脚本用 Redis TIME 按旧规则结算已有令牌，再将余额限制到新容量；无旧状态时才初始化令牌。
     * 新速率为 0 时余额置 0，阻止继续通过。策略的 validUntil 是业务有效期，键清理 TTL 是另一回事。
     * <p>
     * 返回 false 只表示本次未确认写入成功，不反向回滚控制器的内存速率；后续 tick 会继续尝试发布。
     *
     * @param rate
     *                             新共享速率，令牌/秒，允许为 0
     * @param capacity
     *                             新共享桶容量，令牌数，必须为正
     * @param validUntilMillis
     *                             绝对截止 Unix 毫秒；0 表示长期有效，但不能作为有效的 ADAPTIVE 共享策略
     * @return 脚本返回 1 为 true；参数拒绝、空结果或数据访问异常为 false
     * @see com.voucherx.config.RedisScriptConfig#admissionPolicyUpdateScript()
     */
    public boolean updateShared(double rate, double capacity, long validUntilMillis) {
        try {
            Long result = redisTemplate.execute(
                    updateScript,
                    List.of(RedisKeys.admissionSharedTokens(),
                            RedisKeys.admissionSharedPolicy()),
                    String.valueOf(rate),
                    String.valueOf(capacity),
                    String.valueOf(validUntilMillis));
            return result != null && result == 1L;
        } catch (DataAccessException ex) {
            return false;
        }
    }
}
