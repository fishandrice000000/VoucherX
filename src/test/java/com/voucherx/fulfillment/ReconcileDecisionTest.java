package com.voucherx.fulfillment;

import com.voucherx.reconcile.ReconcileDecision;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReconcileDecisionTest {
    private RequestRecord request(String phase, String reservation) {
        return new RequestRecord("r", "e", 1, 1, phase, reservation, 1, "SINGLE", 0, 1, null, null, 0, 0);
    }

    @Test
    void databaseSuccessSyncsCacheButConflictingFactsStayIsolated() {
        assertThat(ReconcileDecision.decide(request("SUCCEEDED", "RESERVED"), true, true, false, "SENT"))
                .isEqualTo(ReconcileDecision.SYNC_RESULT);
        assertThat(ReconcileDecision.decide(request("CLOSED", "RESERVED"), true, true, true, "SENT"))
                .isEqualTo(ReconcileDecision.ISOLATE);
        assertThat(ReconcileDecision.decide(request("SUCCEEDED", "RESERVED"), false, false, true, "SENT"))
                .isEqualTo(ReconcileDecision.ISOLATE);
    }

    @Test
    void intentRequiresPositiveOwnershipEvidence() {
        assertThat(ReconcileDecision.decide(request("INTENT", "UNKNOWN"), false, false, true, null))
                .isEqualTo(ReconcileDecision.ACCEPT_RESERVATION);
        assertThat(ReconcileDecision.decide(request("INTENT", "UNKNOWN"), false, false, false, null))
                .isEqualTo(ReconcileDecision.ISOLATE);
    }

    @Test
    void onlyClosedReservedRequestCanBeReleased() {
        assertThat(ReconcileDecision.decide(request("CLOSED", "RESERVED"), false, false, true, "DONE"))
                .isEqualTo(ReconcileDecision.RELEASE_RESERVATION);
        assertThat(ReconcileDecision.decide(request("ACCEPTED", "RESERVED"), false, false, true, "SENT"))
                .isEqualTo(ReconcileDecision.REPLAY_EVENT);
        assertThat(ReconcileDecision.decide(request("CLOSED", "RELEASED"), false, false, false, "DONE"))
                .isEqualTo(ReconcileDecision.WAIT);
    }

    @Test
    void quarantinedAndUntraceableRequestsAreNotAssumedFailed() {
        assertThat(ReconcileDecision.decide(request("ACCEPTED", "RESERVED"), false, false, true, "QUARANTINED"))
                .isEqualTo(ReconcileDecision.ISOLATE);
        assertThat(ReconcileDecision.decide(request("ACCEPTED", "RESERVED"), false, false, true, null))
                .isEqualTo(ReconcileDecision.ISOLATE);
    }

    @Test
    void pendingDeliveryWaitsForDispatcher() {
        assertThat(ReconcileDecision.decide(request("ACCEPTED", "RESERVED"), false, false, true, "PENDING"))
                .isEqualTo(ReconcileDecision.WAIT);
    }
}
