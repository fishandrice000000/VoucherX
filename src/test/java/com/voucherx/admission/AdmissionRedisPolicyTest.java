package com.voucherx.admission;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.common.redis.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 下验证准入及策略 Lua 的组件契约；继承集成环境，不属于 unit profile。
 * <p>
 * 重点看拒绝分支推进 ts、防止重复补充，以及 ADAPTIVE 共享策略缺失时不补永久默认策略。
 * 每个用例自行传入 AdmissionProperties，和应用的默认 OFF 模式分开；测试不验证性能启用收益。
 */
class AdmissionRedisPolicyTest extends AbstractIntegrationTest {

    private static final long VOUCHER_ID = 2199L;
    private static final AdmissionProperties.Controller CONTROLLER = new AdmissionProperties.Controller(2000, 100, 10,
            400, 10, 0.8,
            1000, 100, 0.9, 3, 10_000, 10_000);

    @Autowired
    private RedisAdmissionStore store;
    @Autowired
    private AdmissionPolicyStore policyStore;

    /**
     * 初始共享容量 1 被消费后立即拒绝；发布共享速率 0 后清空可用令牌，即使容量设为 10 也不放行。
     */
    @Test
    void sharedCapacityIsConsumedOnceAndZeroRateStopsRefill() {
        AdmissionProperties props = properties(1, 1, 1, 1);

        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.ALLOWED);
        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.SHARED_REJECTED);

        assertThat(policyStore.updateShared(0, 10, 0)).isTrue();
        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.SHARED_REJECTED);
    }

    /**
     * 共享容量 10、活动容量 1；第一次通过后第二次应明确为 ACTIVITY_REJECTED，展示两层预算分别判定。
     */
    @Test
    void activityCapacityIsIndependentFromSharedCapacity() {
        AdmissionProperties props = properties(10, 10, 1, 1);

        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.ALLOWED);
        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.ACTIVITY_REJECTED);
    }

    /**
     * 将共享桶设为约 0.6 秒前的空桶，首次拒绝仍须推进 ts；100 毫秒后仍不足一枚，不能把上一段经过时间重复补充。
     *
     * @throws Exception
     *                       测试等待被中断等异常
     */
    @Test
    void rejectedRequestAdvancesTimestampAndDoesNotDoubleRefill() throws Exception {
        AdmissionProperties props = properties(1, 1, 1, 1, AdmissionMode.FIXED);
        assertThat(policyStore.updateShared(1, 1, 0)).isTrue();

        long before = System.currentTimeMillis();
        redisTemplate.opsForHash().put(RedisKeys.admissionSharedTokens(), "tokens", "0");
        redisTemplate.opsForHash().put(RedisKeys.admissionSharedTokens(), "ts",
                String.valueOf(before - 600));

        // 第一次拒绝时余额约为 0.6；旧实现把 ts 留在 before-600，
        // 100ms 后会把 0.6 再加上 0.7 而错误放行。
        assertThat(store.tryAcquire(VOUCHER_ID, props))
                .isEqualTo(AdmissionDecision.SHARED_REJECTED);
        String tsAfterRejection = (String) redisTemplate.opsForHash()
                .get(RedisKeys.admissionSharedTokens(), "ts");
        assertThat(Long.parseLong(tsAfterRejection)).isGreaterThanOrEqualTo(before);

        Thread.sleep(100);
        assertThat(store.tryAcquire(VOUCHER_ID, props))
                .isEqualTo(AdmissionDecision.SHARED_REJECTED);
    }

    /**
     * 空桶按每秒 1 枚运行约 1.2 秒，连续尝试最多通过 1 次，回归拒绝时间戳重复结算问题。
     *
     * @throws Exception
     *                       测试等待被中断等异常
     */
    @Test
    void sustainedAcquireDoesNotExceedConfiguredRate() throws Exception {
        AdmissionProperties props = properties(1, 1, 1, 1, AdmissionMode.FIXED);
        assertThat(policyStore.updateShared(1, 1, 0)).isTrue();
        long before = System.currentTimeMillis();
        redisTemplate.opsForHash().put(RedisKeys.admissionSharedTokens(), "tokens", "0");
        redisTemplate.opsForHash().put(RedisKeys.admissionSharedTokens(), "ts",
                String.valueOf(before));

        int allowed = 0;
        for (int i = 0; i < 12; i++) {
            if (store.tryAcquire(VOUCHER_ID, props) == AdmissionDecision.ALLOWED) {
                allowed++;
            }
            Thread.sleep(100);
        }
        // 初始空桶、1 token/s、1.2s 窗口：正确结算最多放行 1 个。
        assertThat(allowed).isLessThanOrEqualTo(1);
    }

    /**
     * 共享策略缺失且模式 ADAPTIVE 时返回 POLICY_STALE，且 Redis 中仍没有被脚本补建永久默认共享策略。
     */
    @Test
    void missingAdaptivePolicyIsRejectedAndNotCreated() {
        AdmissionProperties props = properties(10, 10, 10, 10, AdmissionMode.ADAPTIVE);

        assertThat(store.tryAcquire(VOUCHER_ID, props))
                .isEqualTo(AdmissionDecision.POLICY_STALE);
        assertThat(redisTemplate.hasKey(RedisKeys.admissionSharedPolicy())).isFalse();
    }

    /**
     * FIXED 第一次申请后，共享/活动的策略和令牌四个键都应有清理 TTL；键 TTL 与策略 validUntil 含义不同。
     */
    @Test
    void fixedPolicyAndTokenKeysReceiveIdleTtl() {
        AdmissionProperties props = properties(10, 10, 10, 10, AdmissionMode.FIXED);

        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.ALLOWED);
        assertThat(redisTemplate.hasKey(RedisKeys.admissionSharedPolicy())).isTrue();
        assertThat(redisTemplate.getExpire(RedisKeys.admissionSharedPolicy())).isPositive();
        assertThat(redisTemplate.getExpire(RedisKeys.admissionSharedTokens())).isPositive();
        assertThat(redisTemplate.getExpire(RedisKeys.admissionActivityPolicy(VOUCHER_ID)))
                .isPositive();
        assertThat(redisTemplate.getExpire(RedisKeys.admissionActivityTokens(VOUCHER_ID)))
                .isPositive();
    }

    /**
     * 预写已过 validUntil 的共享策略，验证 FIXED 模式也返回 POLICY_STALE，遵守策略的有限有效期。
     */
    @Test
    void staleAdaptivePolicyRejectsInsteadOfUsingOldRate() {
        AdmissionProperties props = properties(10, 10, 10, 10);
        assertThat(policyStore.updateShared(100, 100,
                System.currentTimeMillis() - 1_000)).isTrue();

        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.POLICY_STALE);
    }

    /**
     * 构造禁用本地桶、启用 Redis 双桶的 FIXED 测试配置；便于直接观察全局脚本结果。
     *
     * @param sharedRate
     *                             共享每秒补充量
     * @param sharedCapacity
     *                             共享容量
     * @param activityRate
     *                             活动每秒补充量
     * @param activityCapacity
     *                             活动容量
     * @return 供组件测试直接传入存储层的配置
     */
    private AdmissionProperties properties(double sharedRate, double sharedCapacity,
            double activityRate, double activityCapacity) {
        return properties(sharedRate, sharedCapacity, activityRate, activityCapacity,
                AdmissionMode.FIXED);
    }

    /**
     * 构造指定模式的测试配置，本地桶关闭，Global.policyTtlMillis 为 0；共享有限策略需要用例另外发布。
     *
     * @param sharedRate
     *                             默认共享每秒补充量
     * @param sharedCapacity
     *                             默认共享容量
     * @param activityRate
     *                             默认活动每秒补充量
     * @param activityCapacity
     *                             默认活动容量
     * @param mode
     *                             FIXED 或 ADAPTIVE
     * @return 供组件测试直接传入脚本适配层的配置
     */
    private AdmissionProperties properties(double sharedRate, double sharedCapacity,
            double activityRate, double activityCapacity,
            AdmissionMode mode) {
        return new AdmissionProperties(mode,
                new AdmissionProperties.Local(false, 1000, 100),
                new AdmissionProperties.Global(true, sharedRate, sharedCapacity,
                        activityRate, activityCapacity, 0),
                CONTROLLER);
    }
}
