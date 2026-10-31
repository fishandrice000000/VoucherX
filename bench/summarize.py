#!/usr/bin/env python3
"""将多次 run-one 结果汇总为对比表和中位数。"""
import statistics
import sys
from pathlib import Path


def parse_metrics(path):
    values = {}
    for line in Path(path).read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def number(values, key):
    try:
        return float(values.get(key, "nan"))
    except ValueError:
        return float("nan")


def main():
    if len(sys.argv) < 2:
        print("用法: summarize.py label1:dir1,dir2 label2:dir3,dir4 ...")
        raise SystemExit(2)
    groups = {}
    for spec in sys.argv[1:]:
        label, dirs = spec.split(":", 1)
        groups[label] = [parse_metrics(Path(d) / "metrics.txt") for d in dirs.split(",")]
    fields = [
        ("order_tps_in_measure_window", "orders_tps"),
        ("http_p99_ms", "http_p99_ms"),
        ("e2e_p99_ms", "e2e_p99_ms"),
        ("mysql_row_lock_waits_delta", "lock_waits"),
        ("max_rabbit_backlog", "max_backlog"),
        ("redis_cpu_percent_of_one_core", "redis_cpu_pct"),
        ("stock_plus_orders", "conservation_sum"),
    ]
    print("label,run," + ",".join(name for _, name in fields))
    for label, runs in groups.items():
        for index, metrics in enumerate(runs, 1):
            print(label + "," + str(index) + "," +
                  ",".join(f"{number(metrics, key):.2f}" for key, _ in fields))
    print()
    print("group,metric,median,min,max")
    for label, runs in groups.items():
        for key, name in fields:
            values = [number(run, key) for run in runs]
            print(f"{label},{name},{statistics.median(values):.2f},"
                  f"{min(values):.2f},{max(values):.2f}")


if __name__ == "__main__":
    main()
