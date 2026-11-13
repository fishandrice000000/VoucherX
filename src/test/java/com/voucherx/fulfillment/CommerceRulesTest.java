package com.voucherx.fulfillment;

import com.voucherx.commerce.CommerceRules;
import com.voucherx.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CommerceRulesTest {
    @Test
    void validatesOwnerShopThresholdAndValidityBoundaries() {
        assertThat(discount(1, 1, 1, 1, 100, 200, 100, "AVAILABLE", 1000, 300, 1000)).isEqualTo(300);
        assertThatThrownBy(() -> discount(1, 2, 1, 1, 100, 200, 100, "AVAILABLE", 1000, 300, 1000))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> discount(1, 1, 1, 2, 100, 200, 100, "AVAILABLE", 1000, 300, 1000))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> discount(1, 1, 1, 1, 100, 200, 100, "AVAILABLE", 1001, 300, 1000))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> discount(1, 1, 1, 1, 100, 200, 200, "AVAILABLE", 1000, 300, 1000))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> discount(1, 1, 1, 1, 100, 200, 99, "AVAILABLE", 1000, 300, 1000))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void lockedAndTerminalCouponsCannotBeConsumedAgain() {
        for (String state : new String[] { "LOCKED", "USED", "EXPIRED" })
            assertThatThrownBy(() -> discount(1, 1, 1, 1, 100, 200, 150, state, 0, 300, 1000))
                    .isInstanceOf(BusinessException.class);
    }

    @Test
    void discountCannotExceedOriginalAmount() {
        assertThat(discount(1, 1, 1, 1, 0, 200, 100, "AVAILABLE", 0, 300, 100)).isEqualTo(100);
    }

    @Test
    void paymentCannotReviveClosedOrderOrCrossDeadline() {
        assertThat(CommerceRules.paymentOutcome("PENDING_PAYMENT", 200, 199, true)).isEqualTo("APPLIED");
        assertThat(CommerceRules.paymentOutcome("PENDING_PAYMENT", 200, 200, true)).isEqualTo("CONFLICT_EXPIRED");
        assertThat(CommerceRules.paymentOutcome("CANCELLED", 200, 100, true)).isEqualTo("CONFLICT_TERMINAL");
        assertThat(CommerceRules.paymentOutcome("LEGACY_CREATED", 200, 100, true)).isEqualTo("CONFLICT_TERMINAL");
    }

    @Test
    void failuresAndDuplicateSuccessDoNotChangePaidState() {
        assertThat(CommerceRules.paymentOutcome("PAID", 200, 300, false)).isEqualTo("FAILED_ATTEMPT");
        assertThat(CommerceRules.paymentOutcome("PAID", 200, 300, true)).isEqualTo("ALREADY_PAID");
    }

    @Test
    void cancellationReturnsOnlyUnexpiredCoupon() {
        assertThat(CommerceRules.releasedState(200, 199)).isEqualTo("AVAILABLE");
        assertThat(CommerceRules.releasedState(200, 200)).isEqualTo("EXPIRED");
    }

    private long discount(long owner, long user, long couponShop, long shop, long from, long until, long now,
            String state, long min, long face, long original) {
        return CommerceRules.discount(owner, user, couponShop, shop, from, until, now, state, min, face, original);
    }
}
