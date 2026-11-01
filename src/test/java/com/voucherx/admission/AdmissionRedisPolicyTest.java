package com.voucherx.admission;

import com.voucherx.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionRedisPolicyTest extends AbstractIntegrationTest {

    private static final long VOUCHER_ID = 2199L;
    private static final AdmissionProperties.Controller CONTROLLER =
            new AdmissionProperties.Controller(2000, 100, 10, 400, 10, 0.8,
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
    void staleAdaptivePolicyRejectsInsteadOfUsingOldRate() {
        AdmissionProperties props = properties(10, 10, 10, 10);
        assertThat(policyStore.updateShared(100, 100,
                System.currentTimeMillis() - 1_000)).isTrue();

        assertThat(store.tryAcquire(VOUCHER_ID, props)).isEqualTo(AdmissionDecision.POLICY_STALE);
    }

    private AdmissionProperties properties(double sharedRate, double sharedCapacity,
                                           double activityRate, double activityCapacity) {
        return new AdmissionProperties(AdmissionMode.FIXED,
                new AdmissionProperties.Local(false, 1000, 100),
                new AdmissionProperties.Global(true, sharedRate, sharedCapacity,
                        activityRate, activityCapacity, 0),
                CONTROLLER);
    }
}
