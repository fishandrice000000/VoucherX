#!/usr/bin/env bash
# 运行方向 02 分阶段压测：快照 -> 采样 -> PhaseLoadClient -> 排空 -> 快照 -> 阶段指标。
# 用法: bench/run-phase.sh <run-id> <voucher-id> <base-rate> <phases> <warmup> <concurrency> [token-offset]
# phases 例如 0.7:60,1.5:120,0.7:120。
set -euo pipefail
if [[ $# -lt 6 || $# -gt 7 ]]; then
  echo "用法: $0 <run-id> <voucher-id> <base-rate> <phases> <warmup> <concurrency> [token-offset]" >&2
  exit 2
fi
run_id="$1"
voucher_id="$2"
base_rate="$3"
phases="$4"
warmup="$5"
concurrency="$6"
token_offset="${7:-0}"
BENCH_ROOT="${BENCH_ROOT:-tmp/02}"
DB_NAME="${DB_NAME:-voucherx02}"
REDIS_DB="${REDIS_DB:-2}"
out="${BENCH_ROOT}/${run_id}"
tokens="${TOKENS_FILE:-${BENCH_ROOT}/tokens.txt}"
mkdir -p "$out"

if [[ ! -f /tmp/voucherx-bench/PhaseLoadClient.class ]]; then
  javac -d /tmp/voucherx-bench bench/PhaseLoadClient.java
fi

phase_seconds=$(python3 - "$phases" <<'PY'
import sys
print(sum(int(part.split(':')[1]) for part in sys.argv[1].split(',')))
PY
)
sample_duration=$((warmup + phase_seconds + 240))

bench/snapshot.sh "$out" before
bench/sample.sh "$out/samples.csv" "$sample_duration" > "$out/sampler.log" 2>&1 &
sampler_pid=$!
docker compose exec -T redis timeout "$sample_duration" \
  redis-cli -n "$REDIS_DB" --latency-history -i 5 > "$out/redis.latency.txt" 2>&1 &
latency_pid=$!
delay_pid=""
if [[ "${DELAY_ENABLED:-0}" = "1" ]]; then
  delay_file="${EXPERIMENT_DELAY_FILE:-/tmp/vx02-delay-ms}"
  delay_ms="${DELAY_MS:-100}"
  delay_start="${DELAY_START_SECONDS:-90}"
  delay_duration="${DELAY_DURATION_SECONDS:-60}"
  (
    sleep "$delay_start"
    echo "$delay_ms" > "$delay_file"
    sleep "$delay_duration"
    echo 0 > "$delay_file"
  ) > "$out/delay-toggle.log" 2>&1 &
  delay_pid=$!
fi

set +e
java -cp /tmp/voucherx-bench PhaseLoadClient \
  --url http://localhost:8080 \
  --voucher "$voucher_id" \
  --tokens "$tokens" \
  --token-offset "$token_offset" \
  --base-rate "$base_rate" \
  --phases "$phases" \
  --warmup "$warmup" \
  --concurrency "$concurrency" \
  --max-inflight 512 \
  --output "$out" 2>&1 | tee "$out/load.log"
load_exit=${PIPESTATUS[0]}
set -e
echo "load_exit=${load_exit}" > "$out/exit-codes.txt"

# PhaseLoadClient 已等待每阶段在途结束；这里再等待队列排空以观测恢复。
drain_start=$(date +%s)
drained=0
for _ in $(seq 1 180); do
  rabbit=$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages messages_unacknowledged 2>/dev/null || true)
  main=$(awk '$1=="voucherx.seckill.order.queue"{print $2}' <<<"$rabbit")
  unacked=$(awk '$1=="voucherx.seckill.order.queue"{print $3}' <<<"$rabbit")
  retry=$(awk '$1=="voucherx.seckill.order.retry.queue"{print $2}' <<<"$rabbit")
  main=${main:-0}; unacked=${unacked:-0}; retry=${retry:-0}
  if (( main == 0 && unacked == 0 && retry == 0 )); then
    drained=1
    break
  fi
  sleep 2
done
drain_seconds=$(( $(date +%s) - drain_start ))
sleep 2
echo "drained=${drained} drain_seconds=${drain_seconds}" >> "$out/exit-codes.txt"

kill "$sampler_pid" 2>/dev/null || true
kill "$latency_pid" 2>/dev/null || true
wait "$sampler_pid" 2>/dev/null || true
wait "$latency_pid" 2>/dev/null || true
if [[ -n "$delay_pid" ]]; then wait "$delay_pid" 2>/dev/null || true; fi

bench/snapshot.sh "$out" after
bench/final-state.sh "$out" "$voucher_id"
{
  echo "request_id,completed_at_ms"
  docker compose exec -T mysql mysql -uvoucherx -pvoucherx -N "$DB_NAME" 2>/dev/null <<SQL | sed 's/\t/,/'
SELECT request_id, completed_at_ms
  FROM tb_voucher_order
 WHERE voucher_id = ${voucher_id};
SQL
} > "$out/orders.csv"
python3 bench/compute-phase.py "$out"
echo "phase run ${run_id} done: ${out}"
