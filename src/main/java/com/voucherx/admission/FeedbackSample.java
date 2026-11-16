package com.voucherx.admission;

/**
 * 一个控制窗口的反馈载体；完成计数和池状态来自当前应用实例，ready 来自主消费队列。
 * <p>
 * 数值必须结合 queueMeasured/hikariMeasured 解释。平均耗时、峰值在途及活跃连接数目前仅观测，
 * AIMD 不以这些字段设置独立阈值，也没有最老消息年龄字段。
 *
 * @param windowMillis
 *                                          实际采样间隔，毫秒
 * @param completedOrders
 *                                          窗口中观察到的新建订单结果数
 * @param duplicateMessages
 *                                          重复投递复用订单的结果数
 * @param failures
 *                                          暂时性或基础设施失败数
 * @param businessFailures
 *                                          明确业务拒绝数，不进入基础设施失败比例
 * @param averageObservedDurationMillis
 *                                          消费结果观察的平均经过毫秒
 * @param inFlight
 *                                          当前执行段数，批处理时按批次执行段计
 * @param maxInFlight
 *                                          上个窗口观察到的执行段峰值
 * @param queueReady
 *                                          主队列等待交付的消息数，不含所有 unacked 或 Outbox
 * @param queueMeasured
 *                                          是否成功取得主队列 ready
 * @param hikariActive
 *                                          已测得的活跃数据库连接数
 * @param hikariPending
 *                                          已测得的等待数据库连接线程数
 * @param hikariMeasured
 *                                          是否成功取得池状态，false 不等于实际零等待
 * @see AdaptiveRateController#evaluate
 */
public record FeedbackSample(
        long windowMillis,
        long completedOrders,
        long duplicateMessages,
        long failures,
        long businessFailures,
        double averageObservedDurationMillis,
        int inFlight,
        int maxInFlight,
        long queueReady,
        boolean queueMeasured,
        int hikariActive,
        int hikariPending,
        boolean hikariMeasured) {

    /**
     * 按实际窗口时长计算新增订单完成速率；不是入口受理 QPS，也不包含重复订单。
     *
     * @return completedOrders 乘 1000 再除窗口毫秒；窗口非正时为 0
     */
    public double completedTps() {
        return windowMillis <= 0 ? 0 : completedOrders * 1000.0 / windowMillis;
    }

    /**
     * 计算 failures/(completedOrders+duplicateMessages+failures)，明确业务拒绝既不进分子也不进分母。
     *
     * @return 基础设施失败比例；没有参与计算的结果时为 0
     */
    public double failureRatio() {
        long outcomes = completedOrders + duplicateMessages + failures;
        return outcomes == 0 ? 0 : (double) failures / outcomes;
    }

    /**
     * 判断队列 ready 或当前执行段是否大于 0；当前控制器据此选择原因标签，不单独决定降速。
     *
     * @return 观察到排队或执行中的工作时为 true
     */
    public boolean workPending() {
        return queueReady > 0 || inFlight > 0;
    }
}
