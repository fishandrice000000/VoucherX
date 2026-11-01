# VoucherX

VoucherX 是面向电商及本地生活场景的高并发优惠券与营销权益平台。V1 以经典「黑马点评」优惠券秒杀语义为蓝本，重点实践并发控制、异步消息、事务一致性和可验证的故障边界。

当前仓库已完成 **V1 基线**：手机号验证码登录、商户缓存查询、Redis Lua + RabbitMQ + MySQL 的优惠券秒杀链路，以及使用 Testcontainers 验证的自动化测试。

在此基础上完成了 **技术深化 01 的 MySQL 库存桶实验**：确认并定位了 MySQL 单库存行锁热点，但修正测量与持久化配置后，BUCKET 模式未达到容量 +20% 门槛，且在 400 req/s 下出现一次退化。因此**默认保持 SINGLE，BUCKET 仅作为显式实验能力**；Redis 侧未发现热点，**没有实现 Redis 库存分片**。

**技术深化 02** 已实现本地令牌桶、Redis 共享/活动双层固定限流和有界消费反馈，以及 AIMD 控制器骨架；默认 `OFF`。正式过载/恢复三组实验尚未执行，自适应未通过启用验收。

## 技术栈

- Java 21、Spring Boot 3.3.5、Maven。
- MySQL 8、MyBatis-Plus 3.5.9。
- Redis 7、RabbitMQ 3.13、Spring AMQP。
- JUnit 5、Spring Boot Test、Testcontainers 1.21.4、Awaitility。
- 模块化单体，不使用 Spring Cloud，不拆分微服务。

## V1 已实现功能

| 模块 | 已实现内容 |
| --- | --- |
| 用户登录 | 手机号验证码登录、Redis 验证码、随机 Token、登录拦截器、登录态续期、请求结束清理用户上下文、验证码手机号冷却与 IP 频率限制 |
| 商户查询 | 按 ID 查询、Cache Aside、空值缓存、TTL 随机偏移、热点缓存互斥重建与安全释放锁 |
| 优惠券秒杀 | Redis Lua 原子预扣、一人一单、RabbitMQ 异步下单、Publisher Confirm、mandatory 路由失败处理、手动 ACK、有界重试、死信队列、MySQL 条件扣库存与唯一约束、异步结果查询 |
| MySQL 库存桶（方向 01，实验） | 活动级 `SINGLE / BUCKET` 模式、128 桶布局、固定起始桶与有界跨桶尝试、实际桶写入订单、桶编号完整性校验、布局初始化与兼容迁移；未通过启用验收，默认不启用 |
| 多级限流（方向 02，实验） | `OFF / FIXED / ADAPTIVE` 模式、本地令牌桶、Redis 共享/活动双层令牌桶、策略 TTL、完成 TPS/队列/连接池反馈、AIMD、429/503 拒绝语义；正式实验未完成，默认 `OFF` |

V1 不包含支付、核销、Redis Stream、Kafka/RocketMQ、Redis 库存分片、批量落库、自动化对账或补偿中心。方向 02 的限流/自适应是默认关闭的实验能力；MySQL 库存桶仅保留为显式配置的实验能力，默认活动继续使用 `SINGLE`。

## 核心链路

```text
客户端
  └─> POST /api/seckill/vouchers/{voucherId}
        └─> 校验活动时间
              └─> Redis Lua：检查库存 + 检查用户资格 + 扣库存 + 占资格 + 写请求状态
                    └─> RabbitMQ Publisher Confirm + mandatory return
                          └─> 手动 ACK Consumer
                                └─> MySQL 事务：SINGLE 扣活动库存 / BUCKET 扣实际桶 + 创建订单
                                      └─> 提交成功后 ACK
                                            └─> GET /api/seckill/requests/{requestId} 查询结果
```

Redis 预扣和 RabbitMQ 发布不是一个原子操作。明确发布失败时会幂等释放预扣；发布确认超时等未知状态不会盲目释放库存。详见 [秒杀链路](docs/v1/seckill-flow.md) 与 [已知限制](docs/v1/known-limitations.md)。

## 快速启动

### 1. 启动基础设施

```bash
docker compose up -d
```

Compose 端口特意避开常见的 `3306 / 6379 / 5672`：

| 服务 | 宿主机端口 |
| --- | --- |
| MySQL 8 | `3307` |
| Redis 7 | `6380` |
| RabbitMQ AMQP | `5673` |
| RabbitMQ 管理台 | `15673`（voucherx / voucherx） |

MySQL 首次启动时会执行 `src/main/resources/db/schema.sql` 和 `seed.sql`，创建一个演示商户与一场 100 库存的秒杀活动。

已有 V1 数据库升级到桶模式/完成时间观测时，依次执行一次：

```bash
docker compose exec -T mysql mysql -uvoucherx -pvoucherx voucherx \
  < src/main/resources/db/migration/02-mysql-buckets.sql
docker compose exec -T mysql mysql -uvoucherx -pvoucherx voucherx \
  < src/main/resources/db/migration/03-order-completed-at.sql
```

迁移脚本只应执行一次；新环境直接使用 `schema.sql`。

### 2. 启动应用

```bash
cp .env.example .env
set -a
source .env
set +a
mvn spring-boot:run
```

默认地址为 `http://localhost:8080`。`AUTH_EXPOSE_CODE=true` 只用于本地开发，会把模拟验证码返回在接口响应中；生产环境必须保持为 `false`。

也可以先打包后运行：

```bash
mvn -DskipTests package
java -jar target/voucherx-0.1.0-SNAPSHOT.jar
```

### 3. 手工验证

```bash
# 商户查询（公开接口）
curl http://localhost:8080/api/shops/1

# 发送验证码（本地开发会返回 code）
curl -X POST http://localhost:8080/api/auth/code \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13900000001"}'

# 登录，替换 <code>
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13900000001","code":"<code>"}'

# 秒杀，替换 <token>
curl -i -X POST http://localhost:8080/api/seckill/vouchers/1 \
  -H 'Authorization: Bearer <token>'

# 查询异步结果，替换 <token> 和 <requestId>
curl http://localhost:8080/api/seckill/requests/<requestId> \
  -H 'Authorization: Bearer <token>'
```

## 接口概览

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/auth/code` | 否 | 发送模拟验证码，带手机号冷却与 IP 限流 |
| `POST` | `/api/auth/login` | 否 | 验证码登录，返回随机 Token |
| `GET` | `/api/auth/me` | 是 | 查询当前用户，并刷新 Token TTL |
| `POST` | `/api/auth/logout` | 是 | 删除登录态 |
| `GET` | `/api/shops/{shopId}` | 否 | 查询商户详情，使用 Redis 缓存 |
| `POST` | `/api/seckill/vouchers/{voucherId}` | 是 | 受理秒杀请求，返回 `202` 与 `requestId` |
| `GET` | `/api/seckill/requests/{requestId}` | 是 | 查询异步处理状态，仅请求所有者可查 |

异步状态：

- `PROCESSING`：请求已受理，消息已确认进入 RabbitMQ，MySQL 尚未给出最终结果。
- `SUCCESS`：MySQL 已存在已提交的订单，返回 `orderId`。
- `FAILED`：已确定失败的请求，例如数据库库存不足、重复用户、重试耗尽。
- `UNKNOWN`：发布确认超时等暂时无法确定的状态，不能据此判断成功或失败。
- 未受理的请求（活动未开始、库存不足、Redis 预扣失败、明确发布失败）直接返回同步错误，不进入异步状态查询。

统一响应格式：

```json
{"code":0,"message":"OK","data":{}}
```

业务错误同时使用合适的 HTTP 状态码与稳定的业务 `code`。

## 测试

```bash
mvn test
```

测试使用 Testcontainers 启动真实 MySQL 8、Redis 7 和 RabbitMQ 3.13，不使用 Mock 替代核心中间件。最近一次执行结果：

```text
AuthIntegrationTest                       4 passed
ShopCacheIntegrationTest                  4 passed
SeckillIntegrationTest                   21 passed
SeckillConsumerReliabilityTest            2 passed
LocalTokenBucketTest                      4 passed
AdaptiveRateControllerTest                6 passed
AdmissionRedisPolicyTest                  3 passed
SeckillAdmissionIntegrationTest           1 passed
SeckillLocalAdmissionIntegrationTest      1 passed
----------------------------------------------------------
合计                                     46 passed, 0 failed, 0 skipped
```

测试覆盖登录、缓存、秒杀时间/库存/一人一单、SINGLE 与 BUCKET 两种模式下 1000 用户争抢 100 张券、桶额度除不尽、局部售罄、布局不匹配、重复消息幂等、事务回滚、路由失败释放预扣、重试与死信、异步结果查询，以及本地/Redis 限流、拒绝前无副作用、AIMD 状态转移和策略过期。详见 [测试说明](docs/v1/testing.md)、[方向 01 测试说明](docs/01/testing-01.md) 与 [方向 02 测试说明](docs/02/testing-02.md)。

## 关键设计

- **Redis 预扣**：Lua 脚本集中管理，原子完成库存检查、资格占用、扣库存和请求状态写入。
- **MySQL 最终防线**：`UPDATE ... WHERE stock > 0` 加条件扣库存；`(voucher_id, user_id)` 唯一约束保证一人一单；`request_id` 唯一约束保证请求幂等。
- **消息成功定义**：只有 Publisher Confirm 为 ACK 且 mandatory 消息成功路由，才算投递成功。
- **ACK 时机**：消费者仅在 MySQL 事务提交成功、或确认是已处理过的重复消息后 ACK；业务失败与重试耗尽进入明确处理路径。
- **状态查询**：先查 MySQL 已提交订单，再参考 Redis 过程状态；不会把未知状态报告成成功或失败。
- **库存初始化**：应用启动时只对“无历史订单且数据库库存仍为初始总库存”的活动做 `SETNX` 初始化；Redis 库存缺失但已有历史订单时拒绝自动重建，暂停该活动受理。BUCKET 模式按桶额度和校验布局后初始化。
- **MySQL 桶模式（实验）**：用户稳定路由到起始桶 `userId % bucketCount`，消费者校验桶编号完整覆盖并尝试后续桶；实际扣减桶写入订单。Redis 仍为单库存 Key；未通过启用验收，默认 `SINGLE`。
- **发布与状态**：发布器依赖 Spring AMQP 的 `CorrelationData.getReturned()` 判定 mandatory 路由失败，不再额外等待 100ms；请求状态使用条件 Lua 转移，迟到状态不能覆盖 `SUCCESS / FAILED / RELEASED`。

## 目录结构

```text
src/main/java/com/voucherx/
├── auth/        # 验证码、登录、Token 拦截器、用户上下文
├── shop/        # 商户查询与 Cache Aside 缓存
├── seckill/     # 秒杀编排、Redis Lua 预扣、请求状态
├── voucher/     # 优惠券与秒杀活动模型
├── order/       # MySQL 订单事务与幂等
├── mq/          # RabbitMQ 拓扑、发布器、消费者
├── common/      # 统一响应、错误码、Redis Key
└── config/      # 配置属性、拦截器注册、Lua 脚本注册

src/main/resources/
├── application.yml
├── db/          # MySQL 表结构、演示数据、增量迁移
└── lua/         # 秒杀预扣/释放/状态、验证码、缓存锁脚本

src/test/java/com/voucherx/
├── auth/        # 登录集成测试
├── shop/        # 缓存集成测试
└── seckill/     # 秒杀、并发、分桶与消息可靠性测试

bench/            # 压测客户端、数据准备、快照、采样与结果汇总脚本
```

## 方向 01 压测摘要

修正后的正式对照使用清空后的独立数据、200k 用户、1,000,000 库存、8 消费者、开环负载、30s 预热 + 120s 测量 × 3 次；端到端延迟对全部受理请求与数据库完成时间做批量关联，并使用独立低频锁探测，避免采样干扰 MySQL。

### 共同可持续速率 200 req/s（中位数）

| 指标 | SINGLE | BUCKET 128 |
| --- | ---: | ---: |
| 成功订单 TPS | 199.08 | 198.47 |
| HTTP P99 | 12ms | 11ms |
| 数据库端到端 P99 | 14ms | 14ms |
| 最大积压 | 2 | 3 |
| 行锁等待 | 605 | 0 |
| 库存守恒 | 1,000,000 | 1,000,000 |

### 400 req/s（中位数）

| 指标 | SINGLE | BUCKET 128 |
| --- | ---: | ---: |
| 成功订单 TPS | 399.23 | 397.02 |
| HTTP P99 | 92ms | 30ms |
| 数据库端到端 P99 | 961ms | 1,003ms |
| 最大积压 | 434 | 408（一次 4,302） |
| 行锁等待 | 48,350 | 0 |

结论：

- 容量提升为 `-0.55%`，未达到 +20%；BUCKET 有一次明显退化，重复性也未通过。
- 单库存行锁热点已定位到 `tb_seckill_voucher.PRIMARY` 的具体活动记录，但在统一放宽持久化配置下不是最终吞吐瓶颈。
- 早期默认持久化下“232 vs 398、+71.5%”属于未满足相同积压约束、端到端采样不完整的初步观察，不作为验收依据。
- 因此不默认启用 BUCKET；Redis 分片未启用。完整协议、持久化配置差异、原始数据与限制见 [方向 01 压测报告](docs/01/benchmark-01.md)。

## 文档

`docs/` 按项目约定仅保留在本地，不纳入 Git；从 Git 克隆的仓库不包含这些文件。

- [文档目录](docs/README.md)
- [V1 文档目录](docs/v1/README.md)
- [架构说明](docs/v1/architecture.md)
- [数据模型](docs/v1/data-model.md)
- [秒杀链路](docs/v1/seckill-flow.md)
- [RabbitMQ 可靠性](docs/v1/rabbitmq-reliability.md)
- [测试说明](docs/v1/testing.md)
- [已知限制](docs/v1/known-limitations.md)
- [后续演进](docs/v1/future-evolution.md)
- [技术深化方向](docs/technical-directions.md)
- [方向 01 文档目录](docs/01/README.md)
- [方向 01 设计](docs/01/design-01.md)
- [方向 01 压测](docs/01/benchmark-01.md)
- [方向 01 测试](docs/01/testing-01.md)
- [方向 01 限制](docs/01/limitations-01.md)
- [Git 提交规范](docs/git-conventions.md)

## 开发约定

开发前阅读 [AGENTS.md](AGENTS.md) 与本地 Git 提交规范。

- 文档变更需同步维护所在目录的 README，新增文档子目录需补充父目录入口。
- 每个独立、可验证的工作单元完成后创建本地提交，使用 Conventional Commits，英文 Type / Scope 配合中文描述。
- 只提交当前任务相关修改，保护已有工作；不得擅自推送或重写历史。
- 功能状态以实际代码与验证结果为准，区分已验证、未验证与尚未实现。
