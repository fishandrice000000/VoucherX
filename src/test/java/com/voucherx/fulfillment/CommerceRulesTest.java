package com.voucherx.fulfillment;

import com.voucherx.commerce.CommerceRules;
import com.voucherx.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 04 交易纯规则的单元测试，直接传入数字时刻、状态和金额，不启动数据库或支付服务。
 * <p>
 * 覆盖归属/商户/门槛、有效期边界、优惠上限及付款分类优先级；不验证行锁、SQL 回滚或付款取消的并发交错。
 *
 * @see com.voucherx.commerce.CommerceRules
 * @see PersistenceContractTest
 */
class CommerceRulesTest {
    /**
     * 生效时刻 from=100 可用；错持有人、错商户、未达门槛、到达 until=200 及早于 from 的时刻都被拒绝。
     */
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

    /**
     * 对 LOCKED、USED、EXPIRED 分别调用消费优惠规则，验证都不能当作 AVAILABLE 再次下单。
     */
    @Test
    void lockedAndTerminalCouponsCannotBeConsumedAgain() {
        for (String state : new String[] { "LOCKED", "USED", "EXPIRED" })
            assertThatThrownBy(() -> discount(1, 1, 1, 1, 100, 200, 150, state, 0, 300, 1000))
                    .isInstanceOf(BusinessException.class);
    }

    /**
     * 原额 100 分、面额 300 分时只抵 100，避免折扣超过原价导致负应付。
     */
    @Test
    void discountCannotExceedOriginalAmount() {
        assertThat(discount(1, 1, 1, 1, 0, 200, 100, "AVAILABLE", 0, 300, 100)).isEqualTo(100);
    }

    /**
     * 待付单截止 200，199 可 APPLIED，200 为 CONFLICT_EXPIRED；CANCELLED
     * 均为终态冲突。
     */
    @Test
    void paymentCannotReviveClosedOrderOrCrossDeadline() {
        assertThat(CommerceRules.paymentOutcome("PENDING_PAYMENT", 200, 199, true)).isEqualTo("APPLIED");
        assertThat(CommerceRules.paymentOutcome("PENDING_PAYMENT", 200, 200, true)).isEqualTo("CONFLICT_EXPIRED");
        assertThat(CommerceRules.paymentOutcome("CANCELLED", 200, 100, true)).isEqualTo("CONFLICT_TERMINAL");
    }

    /**
     * PAID 收到失败声明优先为 FAILED_ATTEMPT，收到新成功声明为 ALREADY_PAID；规则分类本身不改数据库。
     */
    @Test
    void failuresAndDuplicateSuccessDoNotChangePaidState() {
        assertThat(CommerceRules.paymentOutcome("PAID", 200, 300, false)).isEqualTo("FAILED_ATTEMPT");
        assertThat(CommerceRules.paymentOutcome("PAID", 200, 300, true)).isEqualTo("ALREADY_PAID");
    }

    /**
     * 释放时 now=199、until=200 返回 AVAILABLE，到达 200 返回 EXPIRED，验证失效终点不包含。
     */
    @Test
    void cancellationReturnsOnlyUnexpiredCoupon() {
        assertThat(CommerceRules.releasedState(200, 199)).isEqualTo("AVAILABLE");
        assertThat(CommerceRules.releasedState(200, 200)).isEqualTo("EXPIRED");
    }

    /**
     * 透传测试参数到真实纯规则函数，使各用例能直接列出归属、时间、状态和金额。
     *
     * @param owner
     *                       券所属用户 ID
     * @param user
     *                       当前用户 ID
     * @param couponShop
     *                       券限定商户 ID
     * @param shop
     *                       消费商户 ID
     * @param from
     *                       生效毫秒刻度
     * @param until
     *                       失效毫秒刻度
     * @param now
     *                       用例当前毫秒刻度
     * @param state
     *                       用户券状态
     * @param min
     *                       使用门槛，分
     * @param face
     *                       面额，分
     * @param original
     *                       原额，分
     * @return 真实规则计算出的优惠额，分
     */
    private long discount(long owner, long user, long couponShop, long shop, long from, long until, long now,
            String state, long min, long face, long original) {
        return CommerceRules.discount(owner, user, couponShop, shop, from, until, now, state, min, face, original);
    }
}
