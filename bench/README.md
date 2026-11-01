# VoucherX 压测工具

本目录只包含方向 01 的可复现实验工具，不参与生产运行。

## 文件

| 文件 | 用途 |
| --- | --- |
| `LoadClient.java` | JDK 21 单文件压测客户端，虚拟线程、开环限速、HTTP 延迟与状态采样 |
| `prepare-data.py` | 生成压测用户 SQL、Token 文件和 Redis RESP 文件 |
| `create-activity.sh` | 创建 SINGLE 或 BUCKET 压测活动并初始化 Redis 库存 |
| `snapshot.sh` | 采集 Redis/MySQL/RabbitMQ/容器/应用快照 |
| `sample.sh` | 每 2 秒轻量采样队列、Redis、应用 CPU；不在负载中查询 performance_schema |
| `lock-probe.sh` | 独立低频锁探测，在峰值附近采样 3 次库存表/索引/记录 |
| `run-one.sh` | 一次完整实验：快照 -> 采样 -> 施压 -> 排空 -> 快照 -> 汇总 |
| `final-state.sh` | 查询订单、库存、桶剩余、重复用户与队列状态 |
| `compute.py` | 汇总一次实验指标 |
| `summarize.py` | 多组实验的中位数/最小/最大对比 |

## 数据准备

```bash
mkdir -p tmp/01
python3 bench/prepare-data.py --users 200000 \
  --tokens-file tmp/01/tokens.txt \
  --sql-file tmp/01/users.sql \
  --resp-file tmp/01/tokens.resp

docker compose exec -T mysql mysql -uvoucherx -pvoucherx voucherx < tmp/01/users.sql
docker compose exec -T redis redis-cli --pipe < tmp/01/tokens.resp
```

Token 文件按用户顺序排列。不同活动可以复用同一批用户，但同一活动内不能复用。

- Token 默认 TTL 较短；一次长实验后前 N 个 Token 可能已在认证拦截器续期后过期。
- 可通过 `TOKEN_OFFSET` 使用未使用的 Token 区段，例如 `TOKEN_OFFSET=60000`。
- 生成时可用 `--ttl 86400`，但应用请求会按登录态配置续期为 30 分钟。

## 启动应用（方向 01 对照）

```bash
mvn -DskipTests package
SPRING_RABBITMQ_LISTENER_SIMPLE_CONCURRENCY=8 \
SPRING_RABBITMQ_LISTENER_SIMPLE_MAX_CONCURRENCY=8 \
  nohup java -jar target/voucherx-0.1.0-SNAPSHOT.jar > /tmp/voucherx-app.log 2>&1 &
pgrep -f '^java -jar target/voucherx-0.1.0-SNAPSHOT.jar$' > /tmp/voucherx-app.pid
```

默认消费者并发为 1；方向 01 对照固定为 8，两组必须一致。`run-one.sh` 依赖 `/tmp/voucherx-app.pid` 采集应用 CPU。

## 运行一次实验

```bash
# 单库存，400 req/s，30s 预热 + 120s 测量
bench/create-activity.sh 9101 1000000
TOKEN_OFFSET=0 bench/run-one.sh single-9101 9101 400 30 120 128 0

# MySQL 128 桶，同样参数
bench/create-activity.sh 9701 1000000 128
TOKEN_OFFSET=60000 bench/run-one.sh cap-bucket128-9701 9701 400 30 120 128 0
```

参数：

```text
run-one.sh <run-id> <voucher-id> <rate> <warmup> <duration> <concurrency> <sample-status>
```

- `rate=0` 使用固定并发闭环；正式对照使用开环正数速率。
- `concurrency` 是压测端并发虚拟线程数，不是应用消费者并发。
- `sample-status` 是旧版状态轮询采样数量；正式对照建议设为 `0`，使用 `accepted_requests.csv` 与数据库 `completed_at_ms` 计算全部受理请求的 e2e。
- `TOKENS_FILE` 环境变量可指定倾斜流量 Token 文件；`TOKEN_OFFSET` 指定起始下标。
- `LOCK_PROBE=1` 才启动低频锁探测；默认关闭，避免干扰吞吐。
- 原始数据写入 `tmp/01/<run-id>/`，被 `.gitignore` 忽略。
- 输出包含 `summary.txt`、`metrics.txt`、`accepted_requests.csv`、`orders.csv`、`e2e_db.csv`、`latencies_ms.csv`、`samples.csv` 和前后快照。

## 汇总

```bash
python3 bench/summarize.py \
  "single400:tmp/01/single-9101,tmp/01/single-9102,tmp/01/single-9103" \
  "bucket128x400:tmp/01/cap-bucket128-9701,tmp/01/cap-bucket128-9702,tmp/01/cap-bucket128-9703"
```

详细结果与结论见 `docs/01/benchmark-01.md`。

## 基准持久化配置

方向 01 修正对照使用 **benchmark-only**：

```sql
SET GLOBAL innodb_flush_log_at_trx_commit = 2;
SET GLOBAL sync_binlog = 0;
```

这样可以隔离库存行锁与主机磁盘 fsync，但降低持久化保证；必须在报告中同时披露默认配置与放宽配置的不同结论。默认容器重启后恢复 `1 / 1`。

## 安全与边界

- 仅清理 `voucher-id >= 9000` 的压测活动；不清理用户已有业务数据。
- `prepare-data.py` 使用 `INSERT IGNORE` 和独立用户 ID 段，避免覆盖已有用户。
- Redis 只写压测 Token、活动库存和资格 Key；不执行 `FLUSHDB`。
- 不启动/停止用户已有容器；Compose 使用本项目容器名。
- 压测端与应用/中间件同机运行，结果只用于相对比较，不是生产容量承诺。
- `tmp/` 中的原始数据、日志和容器数据不提交 Git。
