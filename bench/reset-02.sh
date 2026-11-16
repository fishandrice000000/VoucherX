#!/usr/bin/env bash
# 清理方向 02 隔离库 voucherx02 的活动/订单与 Redis DB2 的秒杀/准入 Key。
# 保留用户与认证 Token，便于跨活动复用压测用户。仅允许显式指定隔离库。
set -euo pipefail
DB_NAME="${DB_NAME:-voucherx02}"
REDIS_DB="${REDIS_DB:-2}"
if [[ "$DB_NAME" != "voucherx02" ]]; then
  echo "拒绝清理非隔离库: $DB_NAME" >&2
  exit 2
fi

docker compose exec -T mysql mysql -uvoucherx -pvoucherx "$DB_NAME" 2>/dev/null <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE tb_purchase_order;
TRUNCATE TABLE tb_seckill_voucher_bucket;
TRUNCATE TABLE tb_seckill_voucher;
TRUNCATE TABLE tb_voucher;
SET FOREIGN_KEY_CHECKS = 1;
SQL

for pattern in 'voucherx:seckill:*' 'voucherx:{admission}:*'; do
  keys=$(docker compose exec -T redis redis-cli -n "$REDIS_DB" --scan --pattern "$pattern" 2>/dev/null | tr -d '\r' || true)
  if [[ -n "$keys" ]]; then
    printf '%s\n' "$keys" | docker compose exec -T redis redis-cli -n "$REDIS_DB" DEL >/dev/null
  fi
done
for queue in voucherx.seckill.order.queue voucherx.seckill.order.retry.queue voucherx.seckill.order.dlq; do
  docker compose exec -T rabbitmq rabbitmqctl -q purge_queue "$queue" >/dev/null 2>&1 || true
done
echo "reset db=$DB_NAME redis_db=$REDIS_DB activities/orders/queues"
