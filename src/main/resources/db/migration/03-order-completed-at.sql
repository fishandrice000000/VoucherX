-- 为订单增加应用侧完成时间（毫秒），用于更精确的端到端延迟观测。
-- 仅需在已有数据库执行一次；新环境直接使用 db/schema.sql。
SET NAMES utf8mb4;

ALTER TABLE tb_voucher_order
    ADD COLUMN completed_at_ms BIGINT NOT NULL DEFAULT 0
        COMMENT '订单落库完成时间，应用毫秒时间戳，用于端到端追踪'
        AFTER status;
