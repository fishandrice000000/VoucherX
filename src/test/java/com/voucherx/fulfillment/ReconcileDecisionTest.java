package com.voucherx.fulfillment;

import com.voucherx.reconcile.ReconcileDecision;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 05 的纯分类单元测试，直接构造请求及事实布尔值，不启动 MySQL、Redis、消息或维护命令。
 * <p>
 * 验证“超时不能直接判失败”的分类前提，不证明数据库关闭或 Lua 释放已经执行。
 *
 * @see com.voucherx.reconcile.ReconcileDecision
 * @see DeliveryComponentTest#durableClosureAndRepeatedCompensationReturnStockOnce()
 */
class ReconcileDecisionTest {
    /**
     * 创建固定身份、代次和布局的请求快照，只切换阶段及预留字段以隔离分类条件。
     *
     * @param phase
     *                        INTENT、ACCEPTED、SUCCEEDED 或 CLOSED 等用例阶段
     * @param reservation
     *                        UNKNOWN、RESERVED 或 RELEASED 等用例预留状态
     * @return 传入纯分类函数的请求
     */
    private RequestRecord request(String phase, String reservation) {
        return new RequestRecord("r", "e", 1, 1, phase, reservation, 1, "SINGLE", 0, 1, null, null, 0, 0);
    }

    /**
     * 有匹配订单且请求成功才建议 SYNC_RESULT；有订单却已关闭或声称成功但无订单均应 ISOLATE，不猜测哪一份事实正确。
     */
    @Test
    void databaseSuccessSyncsCacheButConflictingFactsStayIsolated() {
        assertThat(ReconcileDecision.decide(request("SUCCEEDED", "RESERVED"), true, true, false, "SENT"))
                .isEqualTo(ReconcileDecision.SYNC_RESULT);
        assertThat(ReconcileDecision.decide(request("CLOSED", "RESERVED"), true, true, true, "SENT"))
                .isEqualTo(ReconcileDecision.ISOLATE);
        assertThat(ReconcileDecision.decide(request("SUCCEEDED", "RESERVED"), false, false, true, "SENT"))
                .isEqualTo(ReconcileDecision.ISOLATE);
    }

    /**
     * INTENT/UNKNOWN 只有在传入归属匹配为 true 时才建议受理，否则隔离；这条测试不自行验证 Redis 归属读取。
     */
    @Test
    void intentRequiresPositiveOwnershipEvidence() {
        assertThat(ReconcileDecision.decide(request("INTENT", "UNKNOWN"), false, false, true, null))
                .isEqualTo(ReconcileDecision.ACCEPT_RESERVATION);
        assertThat(ReconcileDecision.decide(request("INTENT", "UNKNOWN"), false, false, false, null))
                .isEqualTo(ReconcileDecision.ISOLATE);
    }

    /**
     * CLOSED/RESERVED 建议进入释放，ACCEPTED/SENT 则建议重投，已 RELEASED 返回 WAIT；建议释放不等于执行 INCR。
     */
    @Test
    void onlyClosedReservedRequestCanBeReleased() {
        assertThat(ReconcileDecision.decide(request("CLOSED", "RESERVED"), false, false, true, "DONE"))
                .isEqualTo(ReconcileDecision.RELEASE_RESERVATION);
        assertThat(ReconcileDecision.decide(request("ACCEPTED", "RESERVED"), false, false, true, "SENT"))
                .isEqualTo(ReconcileDecision.REPLAY_EVENT);
        assertThat(ReconcileDecision.decide(request("CLOSED", "RELEASED"), false, false, false, "DONE"))
                .isEqualTo(ReconcileDecision.WAIT);
    }

    /**
     * 已受理但事件 QUARANTINED 或缺失时建议 ISOLATE，不把投递异常当作可自动关闭返还的事实。
     */
    @Test
    void quarantinedAndUntraceableRequestsAreNotAssumedFailed() {
        assertThat(ReconcileDecision.decide(request("ACCEPTED", "RESERVED"), false, false, true, "QUARANTINED"))
                .isEqualTo(ReconcileDecision.ISOLATE);
        assertThat(ReconcileDecision.decide(request("ACCEPTED", "RESERVED"), false, false, true, null))
                .isEqualTo(ReconcileDecision.ISOLATE);
    }

    /**
     * 已受理且事件仍 PENDING 时建议 WAIT，给发送器继续处理；不重复受理或提前释放。
     */
    @Test
    void pendingDeliveryWaitsForDispatcher() {
        assertThat(ReconcileDecision.decide(request("ACCEPTED", "RESERVED"), false, false, true, "PENDING"))
                .isEqualTo(ReconcileDecision.WAIT);
    }
}
