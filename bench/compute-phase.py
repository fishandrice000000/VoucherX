#!/usr/bin/env python3
"""方向 02 分阶段结果汇总：阶段 HTTP、队列和数据库端到端延迟。"""
import csv
import math
import sys
from collections import defaultdict
from pathlib import Path


def percentile(values, p):
    if not values:
        return "NOT_MEASURED"
    values = sorted(values)
    index = max(0, min(len(values) - 1, math.ceil(p / 100 * len(values)) - 1))
    return f"{values[index]:.3f}"


def read_samples(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            try:
                rows.append({
                    "ts": int(row["timestamp"]),
                    "main": int(row["rabbit_main"]) if row["rabbit_main"] else 0,
                    "unacked": int(row["rabbit_main_unacked"]) if row["rabbit_main_unacked"] else 0,
                    "retry": int(row["rabbit_retry"]) if row["rabbit_retry"] else 0,
                    "dlq": int(row["rabbit_dlq"]) if row["rabbit_dlq"] else 0,
                })
            except (ValueError, KeyError):
                continue
    return rows


def main(out_dir):
    out = Path(out_dir)
    phases = []
    with open(out / "phase-summary.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            phases.append(row)
    samples = read_samples(out / "samples.csv") if (out / "samples.csv").exists() else []
    orders = {}
    with open(out / "orders.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            orders[row["request_id"]] = int(row["insert_started_at_ms"])

    accepted_by_phase = defaultdict(list)
    with open(out / "accepted_requests.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            accepted_by_phase[row["phase"]].append(
                (row["requestId"], int(row["start_epoch_ms"])))

    result = []
    for row in phases:
        name = row["phase"]
        start_ms = int(row["start_epoch_ms"])
        # 阶段区间以配置时长为准；阶段结束后的排空时间不属于该阶段。
        end_ms = start_ms + int(row["configured_seconds"]) * 1000
        phase_samples = [s for s in samples if start_ms / 1000 <= s["ts"] <= end_ms / 1000]
        max_main = max((s["main"] for s in phase_samples), default="NOT_MEASURED")
        max_unacked = max((s["unacked"] for s in phase_samples), default="NOT_MEASURED")
        max_retry = max((s["retry"] for s in phase_samples), default="NOT_MEASURED")
        e2e = []
        missing = 0
        for request_id, start in accepted_by_phase.get(name, []):
            insert_started = orders.get(request_id)
            if insert_started is None or insert_started <= 0:
                missing += 1
            else:
                e2e.append(insert_started - start)
        result.append({
            "phase": name,
            "target_rate": row["target_rate"],
            "accepted": row["accepted"],
            "rejected": row["rejected"],
            "errors": row["errors"],
            "skipped": row["skipped"],
            "http_p99_ms": row["http_p99_all_ms"],
            "e2e_samples": len(e2e),
            "e2e_endpoint": "insert_started_at_ms",
            "e2e_missing": missing,
            "e2e_p50_ms": percentile(e2e, 50),
            "e2e_p95_ms": percentile(e2e, 95),
            "e2e_p99_ms": percentile(e2e, 99),
            "max_main_ready": max_main,
            "max_main_unacked": max_unacked,
            "max_retry": max_retry,
            "queue_samples": len(phase_samples),
        })

    fields = list(result[0].keys()) if result else []
    with open(out / "phase-metrics.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(result)
    with open(out / "phase-metrics.txt", "w", encoding="utf-8") as f:
        for row in result:
            f.write(" ".join(f"{k}={v}" for k, v in row.items()) + "\n")
    print((out / "phase-metrics.csv").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
