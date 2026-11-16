package com.voucherx.recovery;

/**
 * 冻结后的发行额度计划：总额 T = 可用 A + 在途受理 R + 已发行 C。
 * <p>
 * 数据库余量已经扣除了 C，尚未扣除只在 Redis 预留的 R，所以 Redis 可用额度 A = 数据库余量 - R。
 * 例如
 * total=100、databaseRemaining=80、createdOrderCount=20、acceptedRequestCount=5，得到
 * A=75、R=5、C=20。
 * 订单付款、取消、核销或过期不减少这里的已发行数量，避免把已经发过的资格再次放出。
 * <p>
 * 本 record 只保存数值；请通过 calculate 校验权威输入，直接构造不会执行守恒校验。
 * 它不自行冻结或锁库，调用者必须保证读取期间发行事实稳定。
 *
 * @param availableStock
 *                                 A，可重新开放抢券的额度
 * @param acceptedRequestCount
 *                                 R，已受理但尚未建单的占用
 * @param createdOrderCount
 *                                 C，已建单的发行数量
 */
public record RecoveryPlan(long availableStock, long acceptedRequestCount, long createdOrderCount) {
    /**
     * 验证数据库发行事实并计算恢复计划；任何无法证明守恒的输入都拒绝继续。
     * <p>
     * 要求各值非负、数据库余量不超过总额、总额减余量等于已建单数、在途占用不超过余量。
     * 不能简单把数据库余量当作 Redis 库存，否则会遗漏在途请求的占用。
     *
     * @param total
     *                                 活动总发行额度 T
     * @param databaseRemaining
     *                                 SINGLE 余量或当前完整分桶布局的余量之和
     * @param createdOrderCount
     *                                 活动全部已建订单数 C
     * @param acceptedRequestCount
     *                                 已归一化的 ACCEPTED 请求数 R
     *
     * @return 通过校验的 A/R/C
     * @throws IllegalStateException
     *                                   输入矛盾或在途额度超过数据库余量
     */
    public static RecoveryPlan calculate(long total, long databaseRemaining, long createdOrderCount,
            long acceptedRequestCount) {
        if (total < 0 || databaseRemaining < 0 || createdOrderCount < 0 || acceptedRequestCount < 0
                || databaseRemaining > total
                || createdOrderCount > total || total - databaseRemaining != createdOrderCount
                || acceptedRequestCount > databaseRemaining)
            throw new IllegalStateException("无法证明发行额度守恒，保持活动冻结");
        return new RecoveryPlan(databaseRemaining - acceptedRequestCount, acceptedRequestCount, createdOrderCount);
    }
}
