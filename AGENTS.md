## 项目基本信息

- **项目名称**：VoucherX
- **项目定位**：面向电商及本地生活场景的高并发优惠券与营销权益平台，以并发处理、异步消息、事务一致性和故障恢复为主要技术研究对象。
- **核心技术栈**：Java 21、Spring Boot、MySQL、Redis、RabbitMQ。

## 文档维护规范

- 新建、修改或删除文档时，必须同步维护该文档所在目录的 `README.md`；不存在时应创建。
- README 应说明该目录的用途，并提供文档索引，确保链接和描述与实际内容一致。
- 新增文档子目录时，应同步更新父目录 README 中的目录入口；父目录没有 README 时应创建。

## Java 代码格式规范

- 项目必须固定一份 Eclipse Formatter 配置，命令行与 VS Code 编辑器共用同一份配置。
- 可直接调用本机已安装的 VS Code Java 扩展内置的 Eclipse JDT Formatter，无需通过编辑器界面触发。
- 所有新增或修改的 Java 文件，提交前必须使用 Formatter 统一格式化，并检查格式化后的差异。
- 代码应保持清晰的缩进、空格和换行，字段、构造器、方法和分支正常展开；不得以沿用旧代码风格为由继续将多条语句挤在同一行。

## 数据访问规范

- 生产代码访问 MySQL 时统一使用 MyBatis / MyBatis-Plus Mapper；Service、定时任务、初始化器和维护入口不得直接使用 JdbcTemplate、Connection、PreparedStatement 或 ResultSet 执行业务 SQL。
- SQL 放在 Mapper 注解或 XML 中；复杂 SQL 使用明确的参数绑定和有业务含义的 Mapper 方法。数据库查询结果使用明确的实体、record 或查询模型，避免使用 Map<String, Object> 表示数据库记录。
- 业务事务边界保留在服务层；修改持久化实现时必须保留行锁及加锁顺序、条件更新、受影响行数判断、幂等与恢复代次检查。
- 测试中的数据准备和独立数据库断言可继续使用 JdbcTemplate；数据源和连接池监控不受上述业务数据访问限制。

## Git 提交规范

本项目所有代码修改必须遵循
`docs/git-conventions.md`。

开始开发前先阅读该文件。

默认在每个独立、可验证的工作单元完成后，
创建符合 Conventional Commits 规范的本地提交。

提交信息使用英文 Type / Scope + 中文描述。

只提交当前任务相关的修改。
不得擅自 Push、重写历史或清理用户已有修改。

每次任务完成时，汇报 Commit Hash、
提交信息以及相关测试结果。
