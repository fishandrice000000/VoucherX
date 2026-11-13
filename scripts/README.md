# 开发与运维脚本

本目录保存本机开发及需要部署凭据的维护工具。

- [format-java.py](format-java.py)：使用本机 Red Hat Java 扩展内置的 Eclipse JDT Formatter 和根目录 `java-formatter.xml` 格式化显式指定的 Java 文件。运行 `python3 scripts/format-java.py 文件.java`，或添加 `--check` 只检查；可用 `VOUCHERX_JAVA_EXTENSION` 指定扩展目录。工具使用扩展自带运行时，不改变项目 Java 21 配置。
- [JavaFormatter.java](JavaFormatter.java)：上述脚本调用的格式化适配器，不属于应用源码。

- [maintenance.sh](maintenance.sh)：调用 Java 维护入口，执行请求诊断、修复、关闭、补偿、活动冻结及恢复；构建后使用，命令详见 [05 说明](../docs/05/implementation-05.md)。活动命令详见 [06 说明](../docs/06/implementation-06.md)。不开放普通用户管理 API。
