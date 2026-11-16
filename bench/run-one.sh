#!/usr/bin/env bash
# 运行一组压测：快照 -> 采样 -> LoadClient -> 排空 -> 快照 -> 最终状态 -> 指标汇总。
# 要求应用已启动，/tmp/voucherx-app.pid 指向应用进程，Docker Compose 基础设施已启动。
# 用法: bench/run-one.sh <run-id> <voucher-id> <rate> <warmup-seconds> <duration-seconds> <concurrency> <sample-status>
set -euo pipefail
if [[ $# -ne 7 ]]; then
  echo "用法: $0 <run-id> <voucher-id> <rate> <warmup> <duration> <concurrency> <sample-status>" >&2
  exit 2
fi
run_id="$1"
voucher_id="$2"
rate="$3"
warmup="$4"
duration="$5"
concurrency="$6"
sample_status="$7"
BENCH_ROOT="${BENCH_ROOT:-tmp/01}"
DB_NAME="${DB_NAME:-voucherx}"
REDIS_DB="${REDIS_DB:-0}"
out="${BENCH_ROOT}/${run_id}"
tokens="${TOKENS_FILE:-${BENCH_ROOT}/tokens.txt}"
mkdir -p "$out"

# 活动必须经过应用初始化器建立完整门禁和库存，再进入正式测量。
gate_state=$(docker compose exec -T redis redis-cli -n "$REDIS_DB" HGET "voucherx:seckill:gate:${voucher_id}" state | tr -d '\r')
if [[ "$gate_state" != "NORMAL" ]]; then
  echo "活动门禁未就绪，请先准备活动并启动应用完成初始化: ${voucher_id}" >&2
  exit 2
fi

javac -d /tmp/voucherx-bench bench/LoadClient.java

bench/snapshot.sh "$out" before
bench/sample.sh "$out/samples.csv" "$((warmup + duration + 120))" > "$out/sampler.log" 2>&1 &
sampler_pid=$!
docker compose exec -T redis timeout "$((warmup + duration + 120))" \
  redis-cli --latency-history -i 5 > "$out/redis.latency.txt" 2>&1 &
latency_pid=$!
lock_probe_pid=""
if [[ "${LOCK_PROBE:-0}" = "1" ]]; then
  bench/lock-probe.sh "$out" "$((warmup + 30))" "$voucher_id" > "$out/lock-probe.log" 2>&1 &
  lock_probe_pid=$!
fi

set +e
java -cp /tmp/voucherx-bench LoadClient \
  --url http://localhost:8080 \
  --voucher "$voucher_id" \
  --tokens "$tokens" \
  --token-offset "${TOKEN_OFFSET:-0}" \
  --rate "$rate" \
  --warmup "$warmup" \
  --duration "$duration" \
  --concurrency "$concurrency" \
  --max-inflight 512 \
  --sample-status "$sample_status" \
  --output "$out" 2>&1 | tee "$out/load.log"
load_exit=${PIPESTATUS[0]}
set -e
echo "load_exit=${load_exit}" > "$out/exit-codes.txt"

# 等待主队列、未 ACK 与重试队列排空；有明确排空上限，避免无限等待。
drain_start=$(date +%s)
drained=0
while (( $(date +%s) - drain_start < ${DRAIN_MAX_SECONDS:-120} )); do
  rabbit=$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages messages_unacknowledged 2>/dev/null || true)
  main=$(awk '$1=="voucherx.seckill.order.queue"{print $2}' <<<"$rabbit")
  unacked=$(awk '$1=="voucherx.seckill.order.queue"{print $3}' <<<"$rabbit")
  retry=$(awk '$1=="voucherx.seckill.order.retry.queue"{print $2}' <<<"$rabbit")
  main=${main:-0}; unacked=${unacked:-0}; retry=${retry:-0}
  unsettled=$(docker compose exec -T mysql mysql -uvoucherx -pvoucherx -N "$DB_NAME" -e "SELECT COUNT(*) FROM tb_seckill_request WHERE voucher_id=${voucher_id} AND phase IN ('INTENT','ACCEPTED')" 2>/dev/null)
  if (( main == 0 && unacked == 0 && retry == 0 && unsettled == 0 )); then
    drained=1
    break
  fi
  sleep 2
done
drain_seconds=$(( $(date +%s) - drain_start ))
sleep 2
echo "drained=${drained} drain_seconds=${drain_seconds}" >> "$out/exit-codes.txt"
echo "drain_start_epoch_s=${drain_start}" >> "$out/exit-codes.txt"

kill "$sampler_pid" 2>/dev/null || true
kill "$latency_pid" 2>/dev/null || true
wait "$sampler_pid" 2>/dev/null || true
wait "$latency_pid" 2>/dev/null || true
if [[ -n "$lock_probe_pid" ]]; then wait "$lock_probe_pid" 2>/dev/null || true; fi

bench/snapshot.sh "$out" after
bench/final-state.sh "$out" "$voucher_id"
{
  echo "request_id,insert_started_at_ms"
  docker compose exec -T mysql mysql -uvoucherx -pvoucherx -N "$DB_NAME" 2>/dev/null <<SQL | sed 's/\t/,/'
SELECT request_id, insert_started_at_ms
  FROM tb_purchase_order
 WHERE voucher_id = ${voucher_id};
SQL
} > "$out/orders.csv"
measure_start=$(awk -F= '$1=="measure_start_epoch_ms"{print $2}' "$out/summary.txt")
measure_end=$(awk -F= '$1=="measure_end_epoch_ms"{print $2}' "$out/summary.txt")
window_orders=$(docker compose exec -T mysql mysql -uvoucherx -pvoucherx -N "$DB_NAME" 2>/dev/null <<SQL
SET time_zone = '+08:00';
SELECT COUNT(*) FROM tb_purchase_order
 WHERE voucher_id = ${voucher_id}
   AND UNIX_TIMESTAMP(create_time) * 1000 >= ${measure_start:-0}
   AND UNIX_TIMESTAMP(create_time) * 1000 < ${measure_end:-0};
SQL
)
echo "window_orders=${window_orders}" > "$out/window-state.txt"
echo "window_orders=${window_orders}"
python3 bench/compute.py "$out" "$voucher_id" "$duration"
echo "run ${run_id} done: ${out}"
