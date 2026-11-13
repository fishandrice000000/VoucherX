package com.voucherx.admission;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.common.redis.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionRedisPolicyTest extends AbstractIntegrationTest {

    private static final long VOUCHER_ID = 2199L;
    private static final AdmissionProperties.Controller CONTROLLER = new AdmissionProperties.Controller(2000, 100, 10,
            400, 10, 0.8,
            1000, 100, 0.9, 3, 10_000, 10_000);

    @Autowired
    private RedisAdmissionStore store;
    @Autowired
    private AdmissionPolicyStore policyStore;

    @Test
    void sharedCapacityIsConsumedOnceAndZeroRateStopsRefill() {
        AdmissionProperties props = properties(1, 1, 1, 1);

        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.ALLOWED);
        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.SHARED_REJECTED);

        assertThat(policyStore.updateShared(0, 10, 0)).isTrue();
        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.SHARED_REJECTED);
    }

    @Test
    void activityCapacityIsIndependentFromSharedCapacity() {
        AdmissionProperties props = properties(10, 10, 1, 1);

        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.ALLOWED);
        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.ACTIVITY_REJECTED);
    }

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

    @Test
    void missingAdaptivePolicyIsRejectedAndNotCreated() {
        AdmissionProperties props = properties(10, 10, 10, 10, AdmissionMode.ADAPTIVE);

        assertThat(store.tryAcquire(VOUCHER_ID, props))
                .isEqualTo(AdmissionDecision.POLICY_STALE);
        assertThat(redisTemplate.hasKey(RedisKeys.admissionSharedPolicy())).isFalse();
    }

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

    @Test
    void staleAdaptivePolicyRejectsInsteadOfUsingOldRate() {
        AdmissionProperties props = properties(10, 10, 10, 10);
        assertThat(policyStore.updateShared(100, 100,
                System.currentTimeMillis() - 1_000)).isTrue();

        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.POLICY_STALE);
    }

    private AdmissionProperties properties(double sharedRate, double sharedCapacity,
            double activityRate, double activityCapacity) {
        return properties(sharedRate, sharedCapacity, activityRate, activityCapacity,
                AdmissionMode.FIXED);
    }

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
