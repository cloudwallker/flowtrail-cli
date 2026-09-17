# v0.1.0 本地验证记录

验证日期：2026-09-16。环境：Windows、OpenJDK 21.0.1、Maven 3.8.5。

| 项目 | 结果 |
| --- | --- |
| Maven clean verify | 成功，包含 Spotless 格式检查与发行打包 |
| JUnit | 30 个测试，0 失败、0 错误、0 跳过 |
| 测试驱动基线 | 首轮 27 个行为测试失败；实现后通过 |
| 审查回归 | 正文超时和完整 URL 引用两项先复现失败，修复后通过 |
| 发行包 PowerShell 冒烟 | 解压后的运行、生成、校验、doctor 均通过 |
| Unix 启动器 | 在 Windows Git Bash 下运行通过 |
| HTTP 演示 | 实际运行 MockServer.java，打包后的 CLI 完成 POST 与结果传递 |
| 失败协议 | 缺失文件退出码 1，stdout 为空，stderr 为有效 JSON |
| 依赖许可 | ZIP 含各依赖许可；JAR 含 META-INF/flowtrail-licenses |
| 代码审查 | 修复后复核，无剩余高／中优先级问题 |

GitHub Actions 已配置 Windows/Linux 测试与标签发布，但本地验证不代表远端 CI 已执行。未在本机验证原生 Linux/macOS，也未发布 GitHub Release。

可重跑：`mvn clean verify`、`scripts/smoke.ps1`，以及 README 中的本地 HTTP 示例。终端演示记录见 `demo.cast`。
