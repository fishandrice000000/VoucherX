package com.voucherx.reconcile;

/**
 * 按 requestId 锁补偿表时取得的执行进度；requestId 由查询参数给出，不包含在此记录组件中。
 * <p>
 * 补偿编号用于追踪，不传入释放 Lua，也不能独自代替原请求/用户/代次的归属检查。
 * 一次请求只有一条补偿意图，重试沿用它；本记录自身不包含活动或恢复代次，需要联合账本核验。
 *
 * @param compensationId
 *                           首次建立意图时的补偿追踪编号
 * @param state
 *                           PENDING、RELEASED 或 ISOLATED；与请求 phase 及诊断 ISOLATE
 *                           区分
 * @param attempts
 *                           已提交到数据库的释放结果次数，事务回滚的本次计数不保留
 * @param nextAtMs
 *                           最早可再次尝试的 Unix 毫秒；终态不会仅因时间到了而自动再执行
 * @param reason
 *                           首次意图保存的释放原因，重复 prepare 不覆盖
 */
public record CompensationRecord(String compensationId, String state, int attempts, long nextAtMs, String reason) {
}
