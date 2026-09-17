# 参与开发

使用 JDK 21+ 与 Maven 3.8.5+。先阅读 README 和 docs/design.md，再为行为变化增加可复现的测试。

- 运行 `mvn clean verify`。
- Java 格式由 Spotless 检查，需要整理时运行 `mvn spotless:apply` 后重新验证。
- HTTP 测试使用回环地址和随机端口，避免依赖真实外部服务。
- 保持 stdout/stderr 与退出码契约；新参数必须更新帮助与 README。
- 修改依赖时同步许可证文件。
- 不提交密钥、个人配置、构建缓存或测试临时文件。
- 提交说明描述真实改动；PR 中写明问题、行为变化和验证命令。
