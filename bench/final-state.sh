#!/usr/bin/env bash
# 保存实验结束后的权威状态与库存守恒相关计数。
set -euo pipefail
out_dir="$1"
voucher_id="$2"
DB_NAME="${DB_NAME:-voucherx}"
REDIS_DB="${REDIS_DB:-0}"
mkdir -p "$out_dir"
{
  echo "# voucher_id=${voucher_id}"
  docker compose exec -T mysql mysql -uvoucherx -pvoucherx -N "$DB_NAME" 2>/dev/null <<SQL
SET time_zone = '+08:00';
SELECT 'orders', COUNT(*) FROM tb_voucher_order WHERE voucher_id = ${voucher_id};
SELECT 'distinct_users', COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id = ${voucher_id};
SELECT 'max_order_id', COALESCE(MAX(id),0) FROM tb_voucher_order WHERE voucher_id = ${voucher_id};
SELECT 'stock_mode', stock_mode FROM tb_seckill_voucher WHERE voucher_id = ${voucher_id};
SELECT 'stock', stock FROM tb_seckill_voucher WHERE voucher_id = ${voucher_id};
SELECT 'total_stock', total_stock FROM tb_seckill_voucher WHERE voucher_id = ${voucher_id};
SELECT 'bucket_count', bucket_count FROM tb_seckill_voucher WHERE voucher_id = ${voucher_id};
SELECT 'bucket_total', COALESCE(SUM(total_stock), 0) FROM tb_seckill_voucher_bucket WHERE voucher_id = ${voucher_id} AND layout_version = 1;
SELECT 'bucket_remaining', COALESCE(SUM(remaining_stock), 0) FROM tb_seckill_voucher_bucket WHERE voucher_id = ${voucher_id} AND layout_version = 1;
SELECT 'duplicate_users', COUNT(*) FROM (
  SELECT user_id, voucher_id, COUNT(*) AS c FROM tb_voucher_order
  WHERE voucher_id = ${voucher_id} GROUP BY user_id, voucher_id HAVING c > 1
) t;
SQL
  echo "redis_stock=$(docker compose exec -T redis redis-cli -n "$REDIS_DB" GET "voucherx:seckill:stock:${voucher_id}" 2>/dev/null | tr -d '\r')"
  echo "redis_users=$(docker compose exec -T redis redis-cli -n "$REDIS_DB" SCARD "voucherx:seckill:users:${voucher_id}" 2>/dev/null | tr -d '\r')"
  echo "rabbit_queues:"
  docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages messages_unacknowledged 2>/dev/null || true
} > "$out_dir/final-state.txt"
cat "$out_dir/final-state.txt"
