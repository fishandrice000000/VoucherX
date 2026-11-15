# VoucherX

VoucherX 是面向电商及本地生活场景的高并发优惠券与营销权益平台。V1 以经典「黑马点评」优惠券秒杀语义为蓝本，重点实践并发控制、异步消息、事务一致性和可验证的故障边界。

当前仓库已完成 **V1 基线**：手机号验证码登录、商户缓存查询、Redis Lua + RabbitMQ + MySQL 的优惠券秒杀链路，以及使用 Testcontainers 验证的自动化测试。

在此基础上完成了 **技术深化 01 的 MySQL 库存桶实验**：确认并定位了 MySQL 单库存行锁热点，但修正测量与持久化配置后，BUCKET 模式未达到容量 +20% 门槛，且在 400 req/s 下出现一次退化。因此**默认保持 SINGLE，BUCKET 仅作为显式实验能力**；Redis 侧未发现热点，**没有实现 Redis 库存分片**。

**技术深化 02** 已实现本地令牌桶、Redis 共享/活动双层固定限流、有界消费反馈和 AIMD 控制器；默认 `OFF`。已修复拒绝分支重复补充令牌、自适应缺失策略退回永久默认值两个正确性问题；默认持久化下 `OFF` 测得 `R=175 req/s`。修复前的 FIXED/ADAPTIVE 探索数据已作废，三组正式重复待重跑；自适应未通过启用验收。

**技术深化 03** 已交付持久化请求意图、受理流水与 Outbox、可恢复投递、事务内请求终态校验和默认关闭的有界批量消费。受理语义改为 MySQL 受理/事件事务提交后返回 202，不再等待 Broker Confirm；必填 `Idempotency-Key` 用于原请求重试。已通过单元测试和一条真实组件受理至落库检查，未做性能验收。详见 [03 实现与使用](docs/03/implementation-03.md)。

**技术深化 04** 已交付购买付款、用户券、消费订单、并发锁券协议、核销、取消和过期处理；模拟支付默认关闭，新购买订单在建单事务中固定权益并进入待付款状态。见 [04 实现与使用](docs/04/implementation-04.md)。

**技术深化 05** 已交付请求诊断、有界扫描、数据库关闭屏障、幂等补偿及修复审计。自动对账默认关闭，维护命令见 [05 实现与使用](docs/05/implementation-05.md)，工具入口见 [scripts](scripts/README.md)。

**技术深化 06** 已交付活动冻结、数据库恢复代次屏障、保留在途受理的库存/资格重建、复流校验与默认关闭的故障注入入口。当前以交付为目标，通过编译、单测及初步组件检查，不代表完整可靠性或性能验收。见 [06 实现与运行](docs/06/implementation-06.md)。

生产数据访问已统一为 MyBatis / MyBatis-Plus：受理、Outbox、付款核销、对账补偿与恢复的 SQL 下沉到 Mapper，购买订单、消费订单、用户券等使用类型化结果。事务及锁协议保留；共享格式规则与命令行工具已接入。此前 MyBatis 重构曾通过 36 个单元测试、52 个真实组件集成测试，未执行正式性能压测。详见本地 [重构说明](docs/mybatis-refactor/README.md)。

本轮兼容清理验证：40 条单元测试、56 条真实组件集成测试通过；两个千用户争抢用例未执行。覆盖严格 Bearer/幂等键协议、完整消息与门禁、初始化与冻结互斥，以及原有下单、支付核销、补偿恢复链路。

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
| MySQL 库存桶（方向 01，实验） | 活动级 `SINGLE / BUCKET` 模式、128 桶布局、固定起始桶与有界跨桶尝试、实际桶写入订单、桶编号完整性校验、完整布局校验与安全初始化；未通过启用验收，默认不启用 |
| 多级限流（方向 02，实验） | `OFF / FIXED / ADAPTIVE` 模式、本地令牌桶、Redis 共享/活动双层令牌桶、策略 TTL、完成 TPS/队列/连接池反馈、AIMD、429/503 拒绝语义；已修复令牌结算/策略缺失问题，正式对照待重跑，默认 `OFF` |

支付核销、批量处理、自动对账与补偿已在 03–06 新增，未引入 Redis Stream、Kafka/RocketMQ 或 Redis 库存分片。方向 02 的限流/自适应是默认关闭的实验能力；MySQL 库存桶仅保留为显式配置的实验能力，默认活动继续使用 `SINGLE`。

## 核心链路

```text
客户端 → 活动/准入校验 → MySQL 请求意图 → Redis Lua 原子预扣
       → MySQL 同事务：可靠受理 + Outbox → 返回 202/requestId
       → Dispatcher：Confirm + 路由校验 + 有界重投
       → Consumer：事务内活动代次/请求终态检查 + 扣库存 + 购买订单 + 请求成功
       → 提交后 ACK → 模拟付款发券 → 消费订单锁券 → 付款核销或取消释放
```

Redis 预扣与 MySQL 受理不是原子事务，持久意图用于扫描窗口。已可靠受理请求发布失败时保留预留并重投/隔离，不直接释放；补偿先以数据库终态屏障关闭请求，再幂等释放。状态查询优先持久化流水和订单，详见 [03](docs/03/implementation-03.md)、[05](docs/05/implementation-05.md)。

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

当前版本只支持全新数据库基线，`schema.sql` 包含全部表、字段和索引，不再提供旧版本增量迁移。已有 Docker 数据卷不会因重启自动升级；请另行准备独立的新环境。本轮清理不会删除、转换本机已有数据或清空消息队列。

接口只接受 `Authorization: Bearer <token>`，抢券必须提供合法的 `Idempotency-Key`。消息必须明确携带库存模式、起始桶、布局版本和恢复代次；缺字段的旧消息会拒绝，查询也不再回退历史订单。

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
  -H 'Authorization: Bearer <token>' \
  -H 'Idempotency-Key: buy-1-user-1'

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
| `POST` | `/api/seckill/vouchers/{voucherId}` | 是 | 必填 `Idempotency-Key`，受理后返回 `202` 与 `requestId` |
| `GET` | `/api/seckill/requests/{requestId}` | 是 | 查询异步处理状态，仅请求所有者可查 |
| `POST` | `/api/consumption-orders` | 是 | 使用本人券创建消费订单并锁券 |
| `GET` | `/api/coupons` | 是 | 本人用户券游标分页 |
| `GET` | `/api/orders/{kind}/{id}` | 是 | 本人购买/消费订单 |
| `POST` | `/api/orders/{kind}/{id}/cancel` | 是 | 取消未付款订单 |
| `POST` | `/api/payments/simulate` | 是 | 默认关闭的模拟付款，kind 为 PURCHASE/CONSUMPTION |

异步状态：

- `PROCESSING`：MySQL 已可靠受理并记录 Outbox，尚未形成最终订单；不代表消息已经确认进入 RabbitMQ。
- `SUCCESS`：MySQL 已存在已提交的订单，返回 `orderId`。
- `FAILED`：已确定失败的请求，例如数据库库存不足、重复用户、重试耗尽。
- `UNKNOWN`：请求意图已持久化，但预留/受理结果暂时无法确定，保留 requestId 供查询或通过同一幂等键重试。
- 活动未开始、库存不足等确定拒绝返回同步错误。消息投递未知与请求受理未知分开保存；已可靠受理后的投递问题不撤销受理。

统一响应格式：

```json
{"code":0,"message":"OK","data":{}}
```

业务错误同时使用合适的 HTTP 状态码与稳定的业务 `code`。

## 测试

交付开发可使用 `mvn -Punit test`，只运行不依赖外部组件的单元测试；
`mvn -Pintegration test` 运行真实组件测试，原有 `mvn test` 仍执行全部测试。
`mvn -DskipTests package` 编译生产及测试源码并打包，不运行测试。

```bash
mvn test
```

此前 03–06 交付记录：36 个单元测试通过；真实 MySQL/Redis/RabbitMQ 下的 6 条功能/恢复/维护 CLI 组件检查通过和 9 个针对性兼容回归。没有执行两个千用户争抢用例、耗时压测、真实进程强杀或完整故障矩阵；该段条数为历史记录；当前兼容清理回归结果见本文开头。

```bash
mvn -Punit clean package
mvn -Dtest=DeliveryComponentTest test
```

新组件检查覆盖受理至落库、幂等键、付款发券/锁券/取消/核销、一次有效补偿、旧代次阻止、重建发布中断续作和本地维护入口。模拟支付、批量、自动对账和故障注入均默认关闭；配置见 application.yml 与 .env.example。

## 关键设计

- **Redis 预扣**：Lua 脚本集中管理，原子完成库存检查、资格占用、扣库存和请求状态写入。
- **MySQL 最终防线**：`UPDATE ... WHERE stock > 0` 加条件扣库存；`(voucher_id, user_id)` 唯一约束保证一人一单；`request_id` 唯一约束保证请求幂等。
- **消息成功定义**：只有 Publisher Confirm 为 ACK 且 mandatory 消息成功路由，才算投递成功。
- **ACK 时机**：消费者仅在 MySQL 事务提交成功、已处理的幂等重复、或持久化终态已确定后 ACK；临时失败通过持久事件安排有界重试，耗尽后隔离，不伪造最终失败。
- **状态查询**：优先读取 MySQL 持久化请求流水，不存在账本时返回请求不存在；Redis 仅保留过程/预留信息，不把未知状态报告为成功或失败。
- **库存初始化**：初始化在活动控制行独占锁内读取配置，先显式建立门禁，再对“无订单、无未结请求且库存未消费”的活动做 `SETNX`；已有库存却门禁缺失或不一致时拒绝自动修复；Redis 库存缺失但已有历史订单时拒绝自动重建，暂停该活动受理。BUCKET 模式按桶额度和校验布局后初始化。
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
├── fulfillment/ # 持久化受理、Outbox、批量消费配置
├── commerce/    # 模拟支付、用户券、锁券核销与取消
├── reconcile/   # 请求诊断、扫描、幂等补偿与审计
├── recovery/    # 活动代次、冻结重建、故障注入
├── maintenance/ # 本地维护 CLI
├── common/      # 统一响应、错误码、Redis Key
└── config/      # 配置属性、拦截器注册、Lua 脚本注册

src/main/resources/
├── application.yml
├── db/          # MySQL 表结构、演示数据、增量迁移
├── mapper/      # MyBatis XML、条件更新与查询映射
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
- [方向 02 文档目录](docs/02/README.md)
- [方向 02 设计](docs/02/design-02.md)
- [方向 02 测试](docs/02/testing-02.md)
- [方向 02 实验协议](docs/02/benchmark-02.md)
- [方向 02 限制](docs/02/limitations-02.md)
- [Git 提交规范](docs/git-conventions.md)

## 开发约定

开发前阅读 [AGENTS.md](AGENTS.md) 与本地 Git 提交规范。

- 生产 MySQL 访问统一使用 Mapper，业务层保留事务边界，数据访问规范见 [AGENTS.md](AGENTS.md#数据访问规范)。
- 文档变更需同步维护所在目录的 README，新增文档子目录需补充父目录入口。
- Java 代码格式遵循 [AGENTS.md](AGENTS.md#java-代码格式规范)：命令行与编辑器共用项目 Formatter 配置，所有新增或修改的 Java 文件提交前必须统一格式化。
- 共享配置为 [java-formatter.xml](java-formatter.xml)，命令行入口见 [scripts](scripts/README.md)。VS Code 设置 `java.format.settings.url` 为 `java-formatter.xml`、`java.format.settings.profile` 为 `VoucherX`；本地 `.vscode/` 继续忽略。
- 每个独立、可验证的工作单元完成后创建本地提交，使用 Conventional Commits，英文 Type / Scope 配合中文描述。
- 只提交当前任务相关修改，保护已有工作；不得擅自推送或重写历史。
- 功能状态以实际代码与验证结果为准，区分已验证、未验证与尚未实现。
