package com.voucherx.voucher.entity;

/**
 * 库存模式。SINGLE 保持 V1 行为；BUCKET 使用 MySQL 库存桶。
 * Redis 在两种模式下都使用单库存 Key，MySQL 桶模式只解决数据库行锁热点。
 */
public enum StockMode {
    SINGLE,
    BUCKET;

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
