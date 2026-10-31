#!/usr/bin/env bash
# 创建压测活动并初始化 Redis 单库存。仅允许 >= 9000 的压测活动 ID。
# 用法: bench/create-activity.sh <voucher-id> <stock> [bucket-count]
# bucket-count 缺省为 1（SINGLE）；>1 时使用 MySQL BUCKET 模式。
set -euo pipefail
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "用法: $0 <voucher-id> <stock> [bucket-count]" >&2
  exit 2
fi
voucher_id="$1"
stock="$2"
bucket_count="${3:-1}"
if (( voucher_id < 9000 )); then
  echo "拒绝操作非压测活动 ID: $voucher_id" >&2
  exit 2
fi
if (( bucket_count < 1 )); then
  echo "bucket-count 必须 >= 1" >&2
  exit 2
fi

if (( bucket_count == 1 )); then
  mode="SINGLE"
  activity_stock="$stock"
  bucket_sql=""
else
  mode="BUCKET"
  activity_stock="0"
  bucket_sql="DELETE FROM tb_seckill_voucher_bucket WHERE voucher_id = ${voucher_id};"
  base=$(( stock / bucket_count ))
  remainder=$(( stock % bucket_count ))
  values=()
  for (( b=0; b<bucket_count; b++ )); do
    amount=$base
    if (( b < remainder )); then amount=$((amount + 1)); fi
    values+=("(${voucher_id}, ${b}, ${amount}, ${amount}, 1)")
  done
  bucket_sql+=$'\n'"INSERT INTO tb_seckill_voucher_bucket (voucher_id, bucket_no, total_stock, remaining_stock, layout_version) VALUES"$'\n'
  bucket_sql+=$(IFS=,; echo "${values[*]}")
  bucket_sql+=";"
fi

docker compose exec -T mysql mysql -uvoucherx -pvoucherx voucherx 2>/dev/null <<SQL
SET NAMES utf8mb4;
DELETE FROM tb_voucher_order WHERE voucher_id = ${voucher_id};
DELETE FROM tb_seckill_voucher_bucket WHERE voucher_id = ${voucher_id};
DELETE FROM tb_seckill_voucher WHERE voucher_id = ${voucher_id};
DELETE FROM tb_voucher WHERE id = ${voucher_id};
INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES (${voucher_id}, 1, '压测秒杀券-${voucher_id}', '', '', 100, 1000, 1, 1);
INSERT INTO tb_seckill_voucher
  (voucher_id, total_stock, stock, stock_mode, bucket_count, layout_version, begin_time, end_time)
VALUES (${voucher_id}, ${stock}, ${activity_stock}, '${mode}', ${bucket_count}, 1,
        NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 24 HOUR);
${bucket_sql}
SQL

docker compose exec -T redis redis-cli SET "voucherx:seckill:stock:${voucher_id}" "${stock}" >/dev/null
docker compose exec -T redis redis-cli DEL "voucherx:seckill:users:${voucher_id}" >/dev/null
echo "activity=${voucher_id} stock=${stock} mode=${mode} buckets=${bucket_count} redis_stock_initialized"
