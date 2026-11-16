package com.voucherx.voucher.entity;

/**
 * 决定异步建单最终扣减哪类 MySQL 库存行；两种模式的 Redis 入口都只有一个活动总库存键。
 * <p>
 * {@link #SINGLE} 扣活动表的一行，{@link #BUCKET} 扣桶表的实际命中行。
 * 分桶旨在分散数据库行锁竞争，不等于 Redis 分片，也不代表已经取得可启用的性能收益。
 *
 * @see com.voucherx.order.service.OrderPersistenceService
 * @see com.voucherx.config.SeckillProperties
 */
public enum StockMode {
    /**
     * 单库存：入口起始桶固定为 0，建单条件扣 tb_seckill_voucher.stock，订单记录 bucket_no=0。
     */
    SINGLE,
    /**
     * 分桶库存：入口按用户取模得出起点，建单有界尝试桶表，订单保存实际扣减成功的桶号。
     */
    BUCKET;

    /**
     * 解析明确的库存模式；接受 SINGLE 或 BUCKET，其他输入抛出参数异常。
     *
     * @param value
     *                  活动或消息中的模式，必须为 SINGLE 或 BUCKET
     * @return 对应的库存策略标识
     * @throws IllegalArgumentException
     *                                      模式缺失或非法
     */
    public static StockMode parse(String value) {
        if (!"SINGLE".equals(value) && !"BUCKET".equals(value))
            throw new IllegalArgumentException("库存模式必须为 SINGLE 或 BUCKET");
        return valueOf(value);
    }

    /**
     * 校验活动的完整布局，再返回模式；入口、初始化、建单和恢复使用相同规则。
     * <p>
     * SINGLE 的桶数必须为 1，BUCKET 的桶数必须为正，布局版本必须为正。
     * BUCKET 的真实桶号覆盖和额度守恒由各自持锁的数据库流程继续检查。
     *
     * @param activity
     *                     当前活动配置
     * @return 已通过基础布局检查的模式
     * @throws IllegalArgumentException
     *                                      模式、桶数或版本缺失或非法
     */
    public static StockMode requireLayout(SeckillVoucher activity) {
        StockMode mode = parse(activity.getStockMode());
        if (activity.getBucketCount() == null || activity.getBucketCount() < 1
                || mode == SINGLE && activity.getBucketCount() != 1
                || activity.getLayoutVersion() == null || activity.getLayoutVersion() < 1)
            throw new IllegalArgumentException("库存桶数或布局版本不合法");
        return mode;
    }
}
