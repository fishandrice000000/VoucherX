#!/usr/bin/env bash
# 低频、一次性锁探测：延迟一段时间后在峰值附近采样 3 次，避免持续查询 performance_schema 干扰压测。
# 用法: bench/lock-probe.sh <输出目录> <延迟秒> <voucher-id>
set -euo pipefail
out_dir="$1"
delay="$2"
voucher_id="$3"
mkdir -p "$out_dir"
out_file="$out_dir/lock-probe.tsv"
sleep "$delay"
: > "$out_file"
for i in 1 2 3; do
  {
    echo -e "sample\t$(date +%s%3N)"
    docker compose exec -T mysql mysql -uroot -proot -N voucherx 2>/dev/null <<SQL
SELECT NOW(3), 'SUMMARY',
       (SELECT COUNT(*) FROM performance_schema.data_lock_waits w
          JOIN performance_schema.data_locks r ON w.REQUESTING_ENGINE_LOCK_ID = r.ENGINE_LOCK_ID
          JOIN performance_schema.data_locks b ON w.BLOCKING_ENGINE_LOCK_ID = b.ENGINE_LOCK_ID
         WHERE r.OBJECT_NAME IN ('tb_seckill_voucher','tb_seckill_voucher_bucket')
            OR b.OBJECT_NAME IN ('tb_seckill_voucher','tb_seckill_voucher_bucket'));
SELECT NOW(3), 'DETAIL',
       r.OBJECT_NAME, r.INDEX_NAME, r.LOCK_TYPE, r.LOCK_MODE, r.LOCK_DATA,
       b.OBJECT_NAME, b.INDEX_NAME, b.LOCK_TYPE, b.LOCK_MODE, b.LOCK_DATA
  FROM performance_schema.data_lock_waits w
  JOIN performance_schema.data_locks r ON w.REQUESTING_ENGINE_LOCK_ID = r.ENGINE_LOCK_ID
  JOIN performance_schema.data_locks b ON w.BLOCKING_ENGINE_LOCK_ID = b.ENGINE_LOCK_ID
 WHERE r.OBJECT_NAME IN ('tb_seckill_voucher','tb_seckill_voucher_bucket')
    OR b.OBJECT_NAME IN ('tb_seckill_voucher','tb_seckill_voucher_bucket')
 LIMIT 20;
SQL
  } >> "$out_file" 2>&1 || true
  sleep 1
done
echo "lock probe done: $voucher_id -> $out_file"
