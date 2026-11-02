-- 03: 持久化受理和 Outbox。已有数据库按文件顺序手动迁移一次。
CREATE TABLE IF NOT EXISTS tb_activity_control (
 voucher_id BIGINT NOT NULL PRIMARY KEY,
 state VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
 epoch BIGINT NOT NULL DEFAULT 1,
 reason VARCHAR(255),
 recovery_id VARCHAR(36),
 update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO tb_activity_control(voucher_id) SELECT voucher_id FROM tb_seckill_voucher;
CREATE TABLE IF NOT EXISTS tb_seckill_request (
 request_id VARCHAR(36) NOT NULL PRIMARY KEY,
 event_id VARCHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,
 voucher_id BIGINT NOT NULL,
 client_key VARCHAR(64) NOT NULL,
 phase VARCHAR(24) NOT NULL DEFAULT 'INTENT',
 reservation VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
 epoch BIGINT NOT NULL,
 stock_mode VARCHAR(16) NOT NULL,
 start_bucket_no INT NOT NULL,
 layout_version INT NOT NULL,
 order_id BIGINT,
 reason VARCHAR(255),
 created_at_ms BIGINT NOT NULL,
 updated_at_ms BIGINT NOT NULL,
 UNIQUE KEY uk_request_client(user_id,client_key),
 UNIQUE KEY uk_request_event(event_id),
 KEY idx_request_scan(phase,updated_at_ms),
 KEY idx_request_activity(voucher_id,phase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_outbox (
 event_id VARCHAR(36) NOT NULL PRIMARY KEY,
 request_id VARCHAR(36) NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_at_ms BIGINT NOT NULL,
 claim_token VARCHAR(36),
 lease_until_ms BIGINT NOT NULL DEFAULT 0,
 last_result VARCHAR(128),
 UNIQUE KEY uk_outbox_request(request_id),
 KEY idx_outbox_due(state,next_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
