-- 从 V1 单库存升级到支持 MySQL 库存桶的增量迁移。
-- 仅需在已有数据库执行一次；新环境直接使用 db/schema.sql。
SET NAMES utf8mb4;

ALTER TABLE tb_seckill_voucher
    ADD COLUMN stock_mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE' COMMENT 'SINGLE 或 BUCKET' AFTER stock,
    ADD COLUMN bucket_count INT NOT NULL DEFAULT 1 COMMENT 'MySQL 库存桶数量' AFTER stock_mode,
    ADD COLUMN layout_version INT NOT NULL DEFAULT 1 COMMENT '库存布局版本' AFTER bucket_count;

ALTER TABLE tb_voucher_order
    ADD COLUMN stock_mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE' COMMENT '下单时库存模式' AFTER voucher_id,
    ADD COLUMN bucket_no INT NOT NULL DEFAULT 0 COMMENT '实际扣减的 MySQL 桶编号' AFTER stock_mode,
    ADD COLUMN layout_version INT NOT NULL DEFAULT 1 COMMENT '下单时库存布局版本' AFTER bucket_no;

CREATE TABLE IF NOT EXISTS tb_seckill_voucher_bucket (
    voucher_id      BIGINT NOT NULL COMMENT '优惠券 ID',
    bucket_no       INT    NOT NULL COMMENT '桶编号，从 0 开始',
    total_stock     INT    NOT NULL COMMENT '桶初始额度',
    remaining_stock INT    NOT NULL COMMENT '桶剩余额度，BUCKET 模式的权威库存',
    layout_version  INT    NOT NULL COMMENT '布局版本',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (voucher_id, bucket_no),
    KEY idx_bucket_layout (voucher_id, layout_version),
    CONSTRAINT chk_bucket_stock CHECK (remaining_stock >= 0 AND remaining_stock <= total_stock)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀 MySQL 库存桶';
