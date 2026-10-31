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
    voucher_id   BIGINT   NOT NULL COMMENT '优惠券 ID',
    total_stock  INT      NOT NULL COMMENT '活动总库存',
    stock        INT      NOT NULL COMMENT '剩余库存，MySQL 为最终权威',
    begin_time   DATETIME NOT NULL COMMENT '秒杀开始时间',
    end_time     DATETIME NOT NULL COMMENT '秒杀结束时间',
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (voucher_id),
    CONSTRAINT chk_seckill_stock CHECK (stock >= 0 AND stock <= total_stock)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀活动库存';

CREATE TABLE IF NOT EXISTS tb_voucher_order (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '订单 ID',
    request_id  VARCHAR(36) NOT NULL COMMENT '一次秒杀请求 ID，用于幂等与状态追踪',
    message_id  VARCHAR(36) NOT NULL COMMENT 'RabbitMQ 消息 ID，用于链路追踪',
    user_id     BIGINT      NOT NULL COMMENT '用户 ID',
    voucher_id  BIGINT      NOT NULL COMMENT '优惠券 ID',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1 已创建',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_request (request_id),
    UNIQUE KEY uk_order_voucher_user (voucher_id, user_id),
    KEY idx_order_message (message_id),
    KEY idx_order_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀订单';
