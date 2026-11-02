# 运维脚本

本目录保存需要部署凭据、在本机显式执行的维护工具。

- [maintenance.sh](maintenance.sh)：调用 Java 维护入口，执行请求诊断、修复、关闭、补偿、活动冻结及恢复；构建后使用，命令详见 [05 说明](../docs/05/implementation-05.md)。活动命令详见 [06 说明](../docs/06/implementation-06.md)。不开放普通用户管理 API。
