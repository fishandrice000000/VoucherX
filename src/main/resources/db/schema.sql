-- 确保初始化脚本按 UTF-8 解释，避免中文种子数据被双重编码。
SET NAMES utf8mb4;

-- VoucherX V1 表结构
-- 说明：V1 只保留登录、商户查询、优惠券秒杀所需的最小数据模型。

CREATE TABLE IF NOT EXISTS tb_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    phone       VARCHAR(11)  NOT NULL COMMENT '手机号',
    nick_name   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称',
    icon        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像地址',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户';

CREATE TABLE IF NOT EXISTS tb_shop (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商户 ID',
    name        VARCHAR(128) NOT NULL COMMENT '商户名称',
    type_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '商户分类',
    images      VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '图片，逗号分隔',
    area        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '商圈',
    address     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '地址',
    avg_price   BIGINT       NOT NULL DEFAULT 0 COMMENT '人均价格，单位分',
    sold        INT          NOT NULL DEFAULT 0 COMMENT '销量',
    comments    INT          NOT NULL DEFAULT 0 COMMENT '评论数',
    score       INT          NOT NULL DEFAULT 0 COMMENT '评分，放大 10 倍',
    open_hours  VARCHAR(128) NOT NULL DEFAULT '' COMMENT '营业时间',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商户';

CREATE TABLE IF NOT EXISTS tb_voucher (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '优惠券 ID',
    shop_id      BIGINT       NOT NULL COMMENT '所属商户',
    title        VARCHAR(128) NOT NULL COMMENT '标题',
    sub_title    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '副标题',
    rules        VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '使用规则',
    pay_value    BIGINT       NOT NULL DEFAULT 0 COMMENT '支付金额，单位分',
    actual_value BIGINT       NOT NULL DEFAULT 0 COMMENT '抵扣金额，单位分',
    type         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 普通券，1 秒杀券',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 上架，0 下架',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_voucher_shop (shop_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '优惠券';

CREATE TABLE IF NOT EXISTS tb_seckill_voucher (
    voucher_id     BIGINT      NOT NULL COMMENT '优惠券 ID',
    total_stock    INT         NOT NULL COMMENT '活动总库存',
    stock          INT         NOT NULL COMMENT '单库存模式剩余库存；BUCKET 模式下不使用该字段作为权威剩余库存',
    stock_mode     VARCHAR(16) NOT NULL DEFAULT 'SINGLE' COMMENT 'SINGLE 或 BUCKET',
    bucket_count   INT         NOT NULL DEFAULT 1 COMMENT 'MySQL 库存桶数量，SINGLE 固定为 1',
    layout_version INT         NOT NULL DEFAULT 1 COMMENT '库存布局版本',
    begin_time     DATETIME    NOT NULL COMMENT '秒杀开始时间',
    end_time       DATETIME    NOT NULL COMMENT '秒杀结束时间',
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (voucher_id),
    CONSTRAINT chk_seckill_stock CHECK (stock >= 0 AND stock <= total_stock)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀活动库存';

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

CREATE TABLE IF NOT EXISTS tb_voucher_order (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '订单 ID',
    request_id  VARCHAR(36) NOT NULL COMMENT '一次秒杀请求 ID，用于幂等与状态追踪',
    message_id     VARCHAR(36) NOT NULL COMMENT 'RabbitMQ 消息 ID，用于链路追踪',
    user_id        BIGINT      NOT NULL COMMENT '用户 ID',
    voucher_id     BIGINT      NOT NULL COMMENT '优惠券 ID',
    stock_mode     VARCHAR(16) NOT NULL DEFAULT 'SINGLE' COMMENT '下单时库存模式',
    bucket_no      INT         NOT NULL DEFAULT 0 COMMENT '实际扣减的 MySQL 桶编号',
    layout_version INT         NOT NULL DEFAULT 1 COMMENT '下单时库存布局版本',
    status         TINYINT     NOT NULL DEFAULT 1 COMMENT '1 已创建',
    completed_at_ms BIGINT     NOT NULL DEFAULT 0 COMMENT '订单落库完成时间，应用毫秒时间戳，用于端到端追踪',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_request (request_id),
    UNIQUE KEY uk_order_voucher_user (voucher_id, user_id),
    KEY idx_order_message (message_id),
    KEY idx_order_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀订单';

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
