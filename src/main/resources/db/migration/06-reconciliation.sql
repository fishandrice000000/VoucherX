-- 05: 有界扫描、可恢复补偿和修复审计。
ALTER TABLE tb_seckill_request ADD COLUMN next_check_at_ms BIGINT NOT NULL DEFAULT 0,
 ADD KEY idx_request_next_check(next_check_at_ms,updated_at_ms);
CREATE TABLE IF NOT EXISTS tb_compensation (
 request_id VARCHAR(36) NOT NULL PRIMARY KEY,
 compensation_id VARCHAR(36) NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_at_ms BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL,
 updated_at_ms BIGINT NOT NULL,
 UNIQUE KEY uk_compensation_id(compensation_id),
 KEY idx_compensation_due(state,next_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_repair_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id VARCHAR(36),
 voucher_id BIGINT NOT NULL,
 action VARCHAR(32) NOT NULL,
 evidence VARCHAR(1024) NOT NULL,
 outcome VARCHAR(128) NOT NULL,
 created_at_ms BIGINT NOT NULL,
 KEY idx_audit_request(request_id,id),
 KEY idx_audit_activity(voucher_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
