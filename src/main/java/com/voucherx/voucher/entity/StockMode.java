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
     * 解析数据库库存模式；空值、空白或未知值均回退 SINGLE，非空值转大写后匹配。
     * <p>
     * 这里只解释配置值，不创建桶，也不检查布局是否完整；不主动去掉非空字符串首尾的空格。
     *
     * @param value
     *                  活动 stock_mode 字段
     * @return 可识别的模式，或兼容回退的 SINGLE
     */
    public static StockMode parse(String value) {
        if (value == null || value.isBlank()) {
            return SINGLE;
        }
        try {
            return StockMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return SINGLE;
        }
    }
}
