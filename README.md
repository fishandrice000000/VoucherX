# VoucherX

VoucherX 是面向电商及本地生活场景的高并发优惠券与营销权益平台，以并发处理、异步消息、事务一致性和故障恢复为主要技术研究对象。

## 当前状态

项目处于需求与规范准备阶段，尚未实现应用代码、构建配置和自动化测试。以下功能与技术方案均为 V1 计划范围。

工程建立并实际验证后，将补充环境搭建、启动方式、接口使用和测试命令。

## 技术栈与架构

- Java 21、Spring Boot 3.x、Maven。
- MySQL 8、MyBatis-Plus。
- Redis 7、RabbitMQ、Spring AMQP。
- JUnit 5、Spring Boot Test、Testcontainers、Docker Compose。

采用模块化单体架构，不拆分微服务。

## V1 业务范围

| 模块 | 计划内容 |
| --- | --- |
| 用户登录 | 手机号验证码登录、Redis 登录态、Token 校验与登录态续期、基础防滥用保护 |
| 商户查询与缓存 | 商户详情查询、Cache Aside、空值缓存、TTL 随机偏移、热点缓存互斥重建 |
| 优惠券秒杀 | Redis Lua 原子预扣、RabbitMQ 异步下单、MySQL 事务持久化、异步结果查询 |

秒杀业务沿用黑马点评的优惠券抢购语义：用户抢购成功后创建秒杀订单，暂不实现后续支付及优惠券核销。

### 核心秒杀链路

```text
用户请求 → Redis Lua 原子预扣 → RabbitMQ 消息投递 → Consumer 消费 → MySQL 事务创建订单
```

用户通过异步结果查询获取处理状态，查询应优先尊重 MySQL 中已经确定的业务终态。

计划通过 Lua 保证 Redis 库存预扣与用户资格占用的原子性，通过 MySQL 条件扣库存、活动与用户组合唯一约束及事务保证最终防超卖和一人一单。

RabbitMQ 计划提供持久化消息、Publisher Confirm、路由失败处理、手动 ACK、幂等消费、有界重试和死信隔离。

Redis 预扣与 RabbitMQ 发布是独立操作，不具备天然的事务一致性。发布确认结果不明时，不能直接释放库存；Redis 预扣成功但发布前宕机等问题，V1 需要明确记录其可靠性边界，完整对账与恢复留待后续版本。

## 范围边界与后续方向

V1 使用 RabbitMQ 替代 Redis Stream，不引入其他消息队列、Spring Cloud、完整支付核销、运营后台或复杂补偿中心。

后续按实际瓶颈与验证结果，逐步研究以下方向，V1 不提前实现：

1. 高并发发券与热点库存分片。
2. 多级限流与自适应背压。
3. 可靠异步发券与批量处理。
4. 完整订单与优惠券核销链路。
5. 自动化一致性对账与补偿。
6. 故障恢复及验证体系。

## 当前目录与文档入口

```text
VoucherX/
├── AGENTS.md       # 项目协作与文档维护规范
├── README.md       # 项目概览
├── .gitignore      # Git 忽略规则
└── docs/           # 本地需求与规范文档，不纳入 Git
```

`docs/` 当前仅保留在本地，以下链接依赖本地文档存在，从 Git 克隆的仓库不包含这些文档：

- [文档目录](docs/README.md)
- [Git 提交规范](docs/git-conventions.md)
- [技术深化方向](docs/technical-directions.md)
- [V1 文档目录](docs/v1/README.md)
- [V1 任务说明](docs/v1/task-v1.md)

## 开发约定

开发前阅读 [AGENTS.md](AGENTS.md) 与本地 Git 提交规范。

- 文档变更需同步维护所在目录的 README，新增文档子目录需补充父目录入口。
- 每个独立、可验证的工作单元完成后创建本地提交，使用 Conventional Commits，英文 Type / Scope 配合中文描述。
- 只提交当前任务相关修改，保护已有工作；不得擅自推送或重写历史。
- 功能状态以实际代码与验证结果为准，区分已验证、未验证与尚未实现。
