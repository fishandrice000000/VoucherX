package com.voucherx.reconcile;

import com.voucherx.fulfillment.RequestRecord;

/**
 * 05 的纯事实分类：超时只触发检查，动作由请求、订单、预留归属及投递状态决定。
 * <p>
 * 此枚举不访问数据库或 Redis，不执行关闭、重投或返还；输入是调用方已经取得的观察结果。
 * ISOLATE 是建议结论，不是请求 phase，也不是补偿记录中的 ISOLATED 状态。
 *
 * @see ReconciliationService#diagnose
 * @see ReconciliationService#repair
 */
public enum ReconcileDecision {
    /**
     * 找到匹配订单且请求已 SUCCEEDED，建议尽力同步 Redis 成功缓存，不再扣库存。
     */
    SYNC_RESULT,
    /**
     * 无订单、请求 INTENT 且观察到原预留归属匹配，建议继续原请求受理。
     */
    ACCEPT_RESERVATION,
    /**
     * 无订单、请求 CLOSED/RESERVED，建议进入持锁补偿流程；不是直接授权 INCR。
     */
    RELEASE_RESERVATION,
    /**
     * 无订单、请求 ACCEPTED 且事件为 SENT，建议按投递预算安排原事件重发。
     */
    REPLAY_EVENT,
    /**
     * 当前事实无需本轮修改，例如已释放或事件仍待处理；不是订单成功证明。
     */
    WAIT,
    /**
     * 事实冲突、证据不足或阶段无法解释，保留诊断结论，不把它猜成业务失败或安全返还。
     */
    ISOLATE;

    /**
     * 按分支优先级选择建议，不以“很久没完成”作为失败事实。
     * <ol>
     * <li>有订单时，仅订单归属匹配且请求 SUCCEEDED 才同步；其他组合一律隔离。</li>
     * <li>无订单但请求声称 SUCCEEDED 时隔离；INTENT 要求 reservationMatches 才继续受理。</li>
     * <li>CLOSED 仅在 reservation=RESERVED 时建议释放，否则等待。</li>
     * <li>ACCEPTED 缺失事件或 QUARANTINED 时隔离，SENT 时建议重投，其余非空投递状态都等待。</li>
     * </ol>
     * <p>
     * CLOSED 分支不以 reservationMatches 决定是否进入补偿；真正释放还须 prepare/execute 与 Lua 重新验证。
     * 本方法不检查活动 NORMAL 或当前代次，repair 和各事务入口另行保护；建议不是跨系统一致快照。
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
