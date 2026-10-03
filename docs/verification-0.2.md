# FlowTrail CLI 0.2 本地验证

日期：2026-10-03（Asia/Shanghai）。环境：Windows 11 amd64、OpenJDK 21.0.1、Maven 3.9.9。源码基于 `c29e744` 的本地工作树；源码摘要与 JAR 哈希见 `verification-artifacts.json`。

| 检查 | 实际结果 |
| --- | --- |
| `mvn spotless:apply clean verify` | BUILD SUCCESS，75 tests，0 failures / errors / skipped |
| 原命令 | 30 项兼容用例通过；解压发行 ZIP 的 run / init / validate / doctor 通过 |
| Agent 与 Provider | 文件读取和工具回填；本地 OpenAI SSE / Ollama NDJSON；参数、调用编号、上下文和超长无换行响应边界 |
| Plan | 屏障证明只读并行，写 Worker 串行；有限返修；已执行写任务不重放且输出可引用 |
| 记忆与索引 | SQLite 重开与项目隔离；工具配对压缩；索引增改删与失败保留；来源/降级/过期标记 |
| 检索敏感路径 | 4 项合成安全用例先失败后通过；Embedding、三种查询与旧缓存均遵守共享路径策略 |
| MCP | stdio / Streamable HTTP 握手和调用，schema 递归、审批拒绝、取消与资源关闭 |
| 许可 | 实际运行依赖与 6 项新增许可/元数据原文哈希核对通过 |

发行功能演示 `scripts/agent_smoke.py` 实际通过 7/7 组检查，包含 19 次独立 CLI 调用：旧任务、索引/检索、Agent/跨进程会话、Plan/汇合、跨进程记忆、写/命令权限、两类 MCP。记录见 [演示 JSON](agent-smoke.json) 和 `target/agent-smoke-report.json`。仅使用本地 mock、临时项目和协议服务，保留工具轨迹与实际命令状态；绝对个人路径已替换为占位符。

两档检索测评在 2026-10-02 执行，各 50 个自动标注标识符问题；HYBRID / KEYWORD / VECTOR 对照与分段 p50/p95、Recall@5、MRR@5、逐题原始结果已保存。详见 [存储与检索](storage-implementation.md) 和 `benchmarks/`。第一遍和预热遍次均为进程内测量。

复验：

```sh
mvn clean verify
python scripts/agent_smoke.py
```

```powershell
./scripts/smoke.ps1
./scripts/verify-runtime-licenses.ps1 -RepositoryPath YOUR_MAVEN_REPOSITORY
```

Maven 与脚本生成的 `target/`、项目 `.flowtrail/` 均属于本地状态，已排除在版本管理和示例发行内容之外。
