#!/usr/bin/env bash
# 采集一次 Redis / MySQL / RabbitMQ / 容器状态快照。
# 用法: bench/snapshot.sh <输出目录> <标签>
set -euo pipefail
out_dir="$1"
label="$2"
DB_NAME="${DB_NAME:-voucherx}"
mkdir -p "$out_dir"
prefix="$out_dir/${label}"
date -Is > "${prefix}.timestamp"

docker compose exec -T redis redis-cli INFO all > "${prefix}.redis.info"
docker compose exec -T redis redis-cli INFO commandstats > "${prefix}.redis.commandstats"

docker compose exec -T mysql mysql -uvoucherx -pvoucherx -N "$DB_NAME" 2>/dev/null > "${prefix}.mysql.status" <<'SQL'
SET time_zone = '+08:00';
SHOW GLOBAL STATUS WHERE Variable_name IN (
  'Questions','Com_select','Com_insert','Com_update','Com_delete',
  'Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_time_max',
  'Innodb_row_lock_current_waits','Innodb_deadlocks',
  'Threads_running','Threads_connected','Slow_queries','Aborted_connects'
);
SQL

docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages messages_unacknowledged consumers > "${prefix}.rabbit.queues"
docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' > "${prefix}.docker.stats" 2>/dev/null || true
if [[ -f /tmp/voucherx-app.pid ]] && kill -0 "$(cat /tmp/voucherx-app.pid)" 2>/dev/null; then
  ps -p "$(cat /tmp/voucherx-app.pid)" -o pid=,%cpu=,rss=,etime= > "${prefix}.app.ps"
fi
echo "snapshot ${label} -> ${out_dir}"
