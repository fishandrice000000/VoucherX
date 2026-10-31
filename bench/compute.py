#!/usr/bin/env python3
"""汇总一次实验的快照、采样、LoadClient 输出与最终状态。"""
import csv
import re
import sys
from pathlib import Path


def read_text(path):
    try:
        return Path(path).read_text(errors="replace")
    except FileNotFoundError:
        return ""


def redis_command_calls(text, command):
    match = re.search(rf"cmdstat_{command}:calls=(\d+),usec=(\d+)", text)
    if not match:
        return 0, 0
    return int(match.group(1)), int(match.group(2))


def redis_info_value(text, key):
    match = re.search(rf"^{re.escape(key)}:(.*)$", text, re.MULTILINE)
    return match.group(1).strip() if match else ""


def mysql_status(text):
    values = {}
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            values[parts[0]] = parts[1]
    return values


def int_value(text, key, default=0):
    match = re.search(rf"^{re.escape(key)}=(\d+)$", text, re.MULTILINE)
    return int(match.group(1)) if match else default


def number_value(text, key, default=0.0):
    match = re.search(rf"^{re.escape(key)}=([-0-9.]+)$", text, re.MULTILINE)
    return float(match.group(1)) if match else default


def summarize(out_dir):
    out = Path(out_dir)
    before_redis = read_text(out / "before.redis.info")
    after_redis = read_text(out / "after.redis.info")
    before_cmd = read_text(out / "before.redis.commandstats")
    after_cmd = read_text(out / "after.redis.commandstats")
    before_mysql = mysql_status(read_text(out / "before.mysql.status"))
    after_mysql = mysql_status(read_text(out / "after.mysql.status"))
    summary = read_text(out / "summary.txt")
    final = read_text(out / "final-state.txt")
    exit_codes = read_text(out / "exit-codes.txt")

    before_eval_calls, before_eval_usec = redis_command_calls(before_cmd, "evalsha")
    after_eval_calls, after_eval_usec = redis_command_calls(after_cmd, "evalsha")
    eval_calls = after_eval_calls - before_eval_calls
    eval_usec = after_eval_usec - before_eval_usec

    def status_delta(key):
        return int(after_mysql.get(key, 0)) - int(before_mysql.get(key, 0))

    redis_cpu_user = float(redis_info_value(after_redis, "used_cpu_user")) - \
        float(redis_info_value(before_redis, "used_cpu_user"))
    redis_cpu_sys = float(redis_info_value(after_redis, "used_cpu_sys")) - \
        float(redis_info_value(before_redis, "used_cpu_sys"))
    start_epoch = int_value(summary, "start_epoch_ms", 0)
    end_epoch = int_value(summary, "end_epoch_ms", 0)
    wall_seconds = max(1.0, (end_epoch - start_epoch) / 1000.0)
    redis_cpu_total = redis_cpu_user + redis_cpu_sys

    max_backlog = 0
    max_unacked = 0
    max_mysql_locks = 0
    max_seckill_locks = 0
    samples_path = out / "samples.csv"
    if samples_path.exists():
        with samples_path.open(newline="") as file:
            for row in csv.DictReader(file):
                for key in ("rabbit_main", "rabbit_retry", "rabbit_dlq"):
                    try:
                        max_backlog = max(max_backlog, int(row.get(key) or 0))
                    except ValueError:
                        pass
                try:
                    max_unacked = max(max_unacked, int(row.get("rabbit_main_unacked") or 0))
                except ValueError:
                    pass
                try:
                    max_mysql_locks = max(max_mysql_locks, int(row.get("mysql_data_lock_waits") or 0))
                    max_seckill_locks = max(max_seckill_locks, int(row.get("mysql_seckill_locks") or 0))
                except ValueError:
                    pass

    def summary_int(key, default=0):
        return int_value(summary, key, default)

    def final_int(key):
        match = re.search(rf"^{re.escape(key)}\s+(\d+)$", final, re.MULTILINE)
        return int(match.group(1)) if match else -1

    orders = final_int("orders")
    stock_mode_match = re.search(r"^stock_mode\s+(\S+)$", final, re.MULTILINE)
    stock_mode = stock_mode_match.group(1) if stock_mode_match else "SINGLE"
    bucket_remaining = final_int("bucket_remaining")
    bucket_total = final_int("bucket_total")
    stock = final_int("stock")
    total_stock = final_int("total_stock")
    authoritative_remaining = bucket_remaining if stock_mode == "BUCKET" else stock
    window_orders = int_value(read_text(out / "window-state.txt"), "window_orders", -1)
    measure_seconds = max(1, summary_int("measure_seconds"))
    distinct_users = final_int("distinct_users")

    lines = [
        f"run_dir={out}",
        f"target_rate={number_value(summary, 'target_rate_per_sec'):.2f}",
        f"warmup_seconds={summary_int('warmup_seconds')}",
        f"measure_seconds={summary_int('measure_seconds')}",
        f"measured_attempts={summary_int('measured_attempts')}",
        f"measured_accepted={summary_int('measured_accepted')}",
        f"measured_rejected={summary_int('measured_rejected')}",
        f"measured_unknown={summary_int('measured_unknown')}",
        f"accept_rate={summary_int('measured_accepted') / measure_seconds:.2f}",
        f"http_p50_ms={summary_int('http_p50_ms')}",
        f"http_p95_ms={summary_int('http_p95_ms')}",
        f"http_p99_ms={summary_int('http_p99_ms')}",
        f"max_dispatch_lag_ms={summary_int('max_dispatch_lag_ms')}",
        f"e2e_samples_done={summary_int('e2e_samples_done')}",
        f"e2e_p50_ms={summary_int('e2e_p50_ms')}",
        f"e2e_p95_ms={summary_int('e2e_p95_ms')}",
        f"e2e_p99_ms={summary_int('e2e_p99_ms')}",
        f"skipped={summary_int('skipped_total')}",
        f"errors={summary_int('errors_total')}",
        f"orders_in_measure_window={window_orders}",
        f"order_tps_in_measure_window={window_orders / measure_seconds:.2f}" if window_orders >= 0 else "order_tps_in_measure_window=-1",
        f"orders_total={orders}",
        f"orders_distinct_users={distinct_users}",
        f"stock_mode={stock_mode}",
        f"stock={stock}",
        f"total_stock={total_stock}",
        f"bucket_total={bucket_total}",
        f"bucket_remaining={bucket_remaining}",
        f"authoritative_remaining={authoritative_remaining}",
        f"stock_plus_orders={authoritative_remaining + orders}",
        f"redis_eval_calls={eval_calls}",
        f"redis_eval_usec={eval_usec}",
        f"redis_eval_usec_per_call={eval_usec / eval_calls:.2f}" if eval_calls else "redis_eval_usec_per_call=0",
        f"wall_seconds={wall_seconds:.2f}",
        f"redis_cpu_user_seconds={redis_cpu_user:.2f}",
        f"redis_cpu_sys_seconds={redis_cpu_sys:.2f}",
        f"redis_cpu_total_seconds={redis_cpu_total:.2f}",
        f"redis_cpu_percent_of_one_core={redis_cpu_total / wall_seconds * 100:.2f}",
        f"redis_eval_calls_per_sec={eval_calls / wall_seconds:.2f}",
        f"mysql_row_lock_waits_delta={status_delta('Innodb_row_lock_waits')}",
        f"mysql_row_lock_time_ms_delta={status_delta('Innodb_row_lock_time')}",
        f"mysql_com_update_delta={status_delta('Com_update')}",
        f"mysql_com_insert_delta={status_delta('Com_insert')}",
        f"mysql_com_select_delta={status_delta('Com_select')}",
        f"max_rabbit_backlog={max_backlog}",
        f"max_rabbit_unacked={max_unacked}",
        f"max_mysql_data_lock_waits={max_mysql_locks}",
        f"max_mysql_seckill_locks={max_seckill_locks}",
        exit_codes.strip(),
    ]
    (out / "metrics.txt").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    summarize(sys.argv[1])
