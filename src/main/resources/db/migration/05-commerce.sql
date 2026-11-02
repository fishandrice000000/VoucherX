-- 04: 历史 CREATED 不自动认定为付款或发券。
ALTER TABLE tb_voucher
 ADD COLUMN min_spend BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN validity_seconds BIGINT NOT NULL DEFAULT 604800;
ALTER TABLE tb_voucher_order
 ADD COLUMN payment_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY_CREATED',
 ADD COLUMN pay_amount BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN coupon_face_value BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN coupon_shop_id BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN coupon_min_spend BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN coupon_validity_seconds BIGINT NOT NULL DEFAULT 604800,
 ADD COLUMN payment_deadline_ms BIGINT NOT NULL DEFAULT 0,
 ADD KEY idx_purchase_expiry(payment_status,payment_deadline_ms);
CREATE TABLE IF NOT EXISTS tb_user_coupon (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 purchase_order_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 voucher_id BIGINT NOT NULL,
 shop_id BIGINT NOT NULL,
 face_value BIGINT NOT NULL,
 min_spend BIGINT NOT NULL,
 valid_from_ms BIGINT NOT NULL,
 valid_until_ms BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
 lock_order_id BIGINT,
 lock_token VARCHAR(36),
 version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_coupon_source(purchase_order_id),
 KEY idx_coupon_user(user_id,id),
 KEY idx_coupon_expiry(state,valid_until_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_consumption_order (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 shop_id BIGINT NOT NULL,
 coupon_id BIGINT NOT NULL,
 lock_token VARCHAR(36) NOT NULL,
 original_amount BIGINT NOT NULL,
 discount_amount BIGINT NOT NULL,
 pay_amount BIGINT NOT NULL,
 payment_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT',
 payment_deadline_ms BIGINT NOT NULL,
 created_at_ms BIGINT NOT NULL,
 KEY idx_consumption_user(user_id,id),
 KEY idx_consumption_expiry(payment_status,payment_deadline_ms),
 CONSTRAINT chk_consume_amount CHECK(original_amount>0 AND discount_amount>=0 AND pay_amount>=0 AND pay_amount+discount_amount=original_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_payment_event (
 event_id VARCHAR(64) NOT NULL PRIMARY KEY,
 order_kind VARCHAR(16) NOT NULL,
 order_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 amount BIGINT NOT NULL,
 success BOOLEAN NOT NULL,
 outcome VARCHAR(32) NOT NULL,
 order_state VARCHAR(24) NOT NULL,
 created_at_ms BIGINT NOT NULL,
 KEY idx_payment_order(order_kind,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_coupon_action (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 coupon_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL,
 lock_token VARCHAR(36) NOT NULL,
 action VARCHAR(16) NOT NULL,
 created_at_ms BIGINT NOT NULL,
 UNIQUE KEY uk_coupon_action(coupon_id,lock_token,action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
