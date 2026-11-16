package com.voucherx.reconcile;

import com.voucherx.fulfillment.RequestRecord;

/**
 * 根据请求、订单、预留归属和投递状态选择对账动作的纯分类器。
 * <p>
 * 调用者提供已读取的观察结果，修复服务负责执行建议。ISOLATE 表示需要隔离查证的建议，补偿执行状态另由补偿记录表示。
 *
 * @see ReconciliationService#diagnose
 * @see ReconciliationService#repair
 */
public enum ReconcileDecision {
    /**
     * 请求为 SUCCEEDED 且匹配订单存在时，同步 Redis 成功结果快照。
     */
    SYNC_RESULT,
    /**
     * 无订单、请求 INTENT 且观察到原预留归属匹配，建议继续原请求受理。
     */
    ACCEPT_RESERVATION,
    /**
     * 请求为 CLOSED/RESERVED 且订单不存在时，进入持锁补偿协议。
     */
    RELEASE_RESERVATION,
    /**
     * 无订单、请求 ACCEPTED 且事件为 SENT，建议按投递预算安排原事件重发。
     */
    REPLAY_EVENT,
    /**
     * 当前事实保持等待，例如预留已释放或事件仍待处理。
     */
    WAIT,
    /**
     * 事实冲突、证据不足或阶段无法解释，保留诊断结论，不把它猜成业务失败或安全返还。
     */
    ISOLATE;

    /**
     * 根据观察结果按优先级选择动作。
     * <ol>
     * <li>订单存在且归属匹配、请求 SUCCEEDED 时同步结果，其他组合隔离查证。</li>
     * <li>订单不存在而请求 SUCCEEDED 时隔离；INTENT 在预留归属明确时继续受理，否则隔离。</li>
     * <li>CLOSED/RESERVED 建议释放，CLOSED 的其他预留状态等待。</li>
     * <li>ACCEPTED 的事件缺失或 QUARANTINED 时隔离，SENT 时建议重投，其他投递状态等待。</li>
     * </ol>
     * <p>
     * 修复入口继续核对活动和代次；释放动作通过
     * closeRequestAndPrepareCompensation、releaseReservationAndRecordResult 和 Lua
     * 校验当前身份及归属。
     *
     * @param r
     *                               已存在的请求账本快照
     * @param hasOrder
     *                               是否按请求 ID 找到订单
     * @param orderMatches
     *                               所找到订单的用户和活动是否匹配
     * @param reservationMatches
     *                               诊断时观察到的 Redis 归属是否匹配，仅影响 INTENT 分支
     * @param outboxState
     *                               观察到的投递状态，可为空
     * @return 本轮建议；不直接改变任何持久化状态
     * @see CompensationService
     */
    public static ReconcileDecision decide(RequestRecord r, boolean hasOrder, boolean orderMatches,
            boolean reservationMatches, String outboxState) {
        if (hasOrder)
            return orderMatches && "SUCCEEDED".equals(r.phase()) ? SYNC_RESULT : ISOLATE;
        if ("SUCCEEDED".equals(r.phase()))
            return ISOLATE;
        if ("INTENT".equals(r.phase()))
            return reservationMatches ? ACCEPT_RESERVATION : ISOLATE;
        if ("CLOSED".equals(r.phase()))
            return "RESERVED".equals(r.reservation()) ? RELEASE_RESERVATION : WAIT;
        if ("ACCEPTED".equals(r.phase())) {
            if (outboxState == null || "QUARANTINED".equals(outboxState))
                return ISOLATE;
            return "SENT".equals(outboxState) ? REPLAY_EVENT : WAIT;
        }
        return ISOLATE;
    }
}
