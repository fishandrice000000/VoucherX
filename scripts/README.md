# 开发与运维脚本

本目录保存本机开发及需要部署凭据的维护工具。

- [format-java.py](format-java.py)：使用本机 Red Hat Java 扩展内置的 Eclipse JDT Formatter 和根目录 `java-formatter.xml` 格式化显式指定的 Java 文件。运行 `python3 scripts/format-java.py 文件.java`，或添加 `--check` 只检查；可用 `VOUCHERX_JAVA_EXTENSION` 指定扩展目录。工具使用扩展自带运行时，不改变项目 Java 21 配置。
- [JavaFormatter.java](JavaFormatter.java)：上述脚本调用的格式化适配器，不属于应用源码。

- [maintenance.sh](maintenance.sh)：调用 [MaintenanceCli](../src/main/java/com/voucherx/maintenance/MaintenanceCli.java)，执行请求诊断、修复、关闭、补偿、活动冻结及恢复。权限由运行环境与数据库、Redis 连接凭据控制。

## 维护命令

先运行 `mvn -DskipTests package`，并在调用终端加载目标环境的连接配置。可用 `VOUCHERX_JAR` 指定应用 JAR；默认使用项目的 `target/voucherx-0.1.0-SNAPSHOT.jar`。

| 命令 | 参数 | 行为 |
| --- | --- | --- |
| `diagnose` | 请求 ID | 只读诊断请求、预留、事件和订单 |
| `repair` | 请求 ID | 根据当前事实选择修复动作 |
| `close` | 请求 ID | 关闭请求并准备补偿 |
| `compensate` | 请求 ID | 执行预留返还并保存结果 |
| `status` | 活动 ID | 查询恢复状态 |
| `freeze` | 活动 ID | 冻结活动并建立恢复屏障 |
| `recover` | 活动 ID | 执行状态重建与复流校验 |

```bash
scripts/maintenance.sh diagnose '<requestId>'
scripts/maintenance.sh status '<voucherId>'
```

维护进程会关闭自身的自动投递、消息消费和后台扫描。`close` 与 `compensate` 是两个独立步骤；关闭返回 `COMPENSATION_PENDING` 表示待补偿，返还结果由后续补偿执行决定。

`repair` 的 `SYNC_ATTEMPTED` 表示已尝试同步缓存，`REDELIVERY_SCHEDULED` 表示事件已回到待投递状态，`EVENT_QUARANTINED` 表示投递预算耗尽后已隔离，`STATE_CHANGED` 表示持锁复核或条件更新时状态已变化。
