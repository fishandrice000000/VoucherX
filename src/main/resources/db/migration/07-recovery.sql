-- 06: 活动恢复的持久阶段和守恒快照。
CREATE TABLE IF NOT EXISTS tb_recovery_run (
 recovery_id VARCHAR(36) NOT NULL PRIMARY KEY,
 voucher_id BIGINT NOT NULL,
 epoch BIGINT NOT NULL,
 state VARCHAR(24) NOT NULL,
 available BIGINT,
 reserved BIGINT,
 confirmed BIGINT,
 created_at_ms BIGINT NOT NULL,
 updated_at_ms BIGINT NOT NULL,
 UNIQUE KEY uk_recovery_epoch(voucher_id,epoch)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
