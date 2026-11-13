package com.voucherx.admission;

import com.voucherx.common.redis.RedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 共享桶与活动桶的 Redis 适配层：一次 Lua 同时检查两桶，只有都够才一起扣 1。
 * <p>
 * 它不执行库存预留；某桶不足时仍保存结算后的余额和时间戳，但不发生仅扣另一桶的部分消费。
 * FIXED 可以按默认配置创建缺失策略；ADAPTIVE 的共享策略必须来自带有限有效期的控制器发布。
 *
 * @see SeckillAdmissionService#acquireGlobal(Long)
 * @see AdmissionPolicyStore
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

    /**
     * 把四个 Hash 键与本次成本、默认速率/容量和模式传给 admission_acquire.lua。
     * <p>
     * KEYS 顺序为共享 tokens、共享 policy、活动 tokens、活动 policy；ARGV 顺序为成本 1、
     * 默认共享 rate/capacity、默认活动 rate/capacity、模式。脚本使用 Redis TIME 结算，Java 不传本机时间。
     * <p>
     * 脚本先验证策略，再按各自速率补充；共享不足返回 -1，活动不足返回 -2，都够才同时扣减并返回 1。
     * 拒绝也写 tokens/ts，避免下次把同一段时间重复补充。
     * <p>
     * ADAPTIVE 缺失、永久或过期共享策略返回 -3，不自动补永久默认策略；活动策略仍可按固定配置创建。
     * 空结果或其他返回值映射 INVALID_POLICY。开关绕过由上层服务负责，本方法本身不跳过 OFF。
     *
     * @param voucherId
     *                       本次活动 ID，用于活动桶键
     * @param properties
     *                       提供模式及共享/活动默认参数的配置
     * @return 仅描述本次全局准入的判定，不描述库存或建单结果
     * @throws AdmissionUnavailableException
     *                                           Redis 数据访问失败
     * @see com.voucherx.config.RedisScriptConfig#admissionAcquireScript()
     * @see com.voucherx.common.redis.RedisKeys
     */
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
                    properties.mode().name());
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

    /**
     * 用一次 hasKey 尝试探测 Redis 调用能否完成，但不检查返回的键是否存在。
     * <p>
     * 因此 true 不能证明共享策略存在、有效或已准备好放行，真正资格仍由 tryAcquire 的脚本判断。
     *
     * @return Redis 查询未抛数据访问异常时为 true；异常时为 false
     */
    public boolean isAvailable() {
        try {
            redisTemplate.hasKey(RedisKeys.admissionSharedPolicy());
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }
}
