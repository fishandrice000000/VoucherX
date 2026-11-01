#!/usr/bin/env bash
# 每 2 秒采样一次轻量运行指标，写入 samples.csv。
# 用法: bench/sample.sh <输出文件> <持续秒数>
set -euo pipefail
out_file="$1"
DB_NAME="${DB_NAME:-voucherx}"
REDIS_DB="${REDIS_DB:-0}"
duration="$2"
mkdir -p "$(dirname "$out_file")"
echo "timestamp,redis_ops,redis_cpu_user,redis_cpu_sys,mysql_threads_running,rabbit_main,rabbit_main_unacked,rabbit_retry,rabbit_dlq,app_cpu" > "$out_file"
end=$(( $(date +%s) + duration ))
while (( $(date +%s) < end )); do
  ts=$(date +%s)
  redis_info=$(docker compose exec -T redis redis-cli -n "$REDIS_DB" INFO 2>/dev/null || true)
  redis_ops=$(awk -F: '/^instantaneous_ops_per_sec:/{gsub(/\r/,"");print $2}' <<<"$redis_info")
  redis_user=$(awk -F: '/^used_cpu_user:/{gsub(/\r/,"");print $2}' <<<"$redis_info")
  redis_sys=$(awk -F: '/^used_cpu_sys:/{gsub(/\r/,"");print $2}' <<<"$redis_info")
  mysql_threads=$(docker compose exec -T mysql mysql -uvoucherx -pvoucherx -N "$DB_NAME" 2>/dev/null <<'SQL' | awk -F'\t' 'NR==1{print $1}' || true
SELECT COUNT(*) FROM information_schema.processlist WHERE COMMAND <> 'Sleep';
SQL
)
  rabbit=$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages messages_unacknowledged 2>/dev/null || true)
  main=$(awk '$1=="voucherx.seckill.order.queue"{print $2}' <<<"$rabbit")
  main_unacked=$(awk '$1=="voucherx.seckill.order.queue"{print $3}' <<<"$rabbit")
  retry=$(awk '$1=="voucherx.seckill.order.retry.queue"{print $2}' <<<"$rabbit")
  dlq=$(awk '$1=="voucherx.seckill.order.dlq"{print $2}' <<<"$rabbit")
  app_cpu=""
  if [[ -f /tmp/voucherx-app.pid ]] && kill -0 "$(cat /tmp/voucherx-app.pid)" 2>/dev/null; then
    app_cpu=$(ps -p "$(cat /tmp/voucherx-app.pid)" -o %cpu= | tr -d ' ')
  fi
  echo "$ts,${redis_ops:-},${redis_user:-},${redis_sys:-},${mysql_threads:-},${main:-},${main_unacked:-},${retry:-},${dlq:-},${app_cpu:-}" >> "$out_file"
  sleep 2
done
