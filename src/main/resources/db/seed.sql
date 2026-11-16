-- 确保初始化脚本按 UTF-8 解释，避免中文种子数据被双重编码。
SET NAMES utf8mb4;

-- 本地演示数据：一个商户和一场秒杀活动。
-- Redis 库存由应用启动时在“安全条件”下初始化，详见 SeckillStockInitializer。

INSERT INTO tb_shop (id, name, type_id, images, area, address, avg_price, sold, comments, score, open_hours)
VALUES (1, 'VoucherX 演示茶餐厅', 1, '/imgs/shop-1.jpg', '中心商圈', '示例路 1 号', 6800, 1200, 356, 47, '10:00-22:00')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, purchase_price, face_value, type, status)
VALUES (1, 1, '100 元秒杀代金券', '限量 100 张', '每用户限购 1 张，仅限演示使用', 1000, 10000, 1, 1)
ON DUPLICATE KEY UPDATE title = VALUES(title);

INSERT INTO tb_seckill_voucher (voucher_id, total_stock, stock, stock_mode, bucket_count, layout_version, begin_time, end_time)
VALUES (1, 100, 100, 'SINGLE', 1, 1, '2024-01-01 00:00:00', '2099-12-31 23:59:59')
ON DUPLICATE KEY UPDATE total_stock = VALUES(total_stock);
