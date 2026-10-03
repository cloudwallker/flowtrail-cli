# FlowTrail CLI：能力、代码与演示

项目名称：**FlowTrail CLI｜命令行 Coding Agent**。技术栈：Java 21、picocli、Jackson、JDK HTTP Client、SQLite JDBC、JavaParser、JUnit 5。

可用于面试的项目描述：

> 在命令行任务执行器上实现工具型 Coding Agent，统一模型消息和 Tool Calling；加入路径与命令策略、调用预算和审计。基于 SQLite 管理项目记忆与会话，使用 JavaParser 语义分块和关键词 / 向量融合检索定位代码。Plan DAG 支持有界只读并行、写任务串行、证据审查与有限返修，并通过 MCP 接入两类传输的外部工具。

| 能力 | 代码入口 | 验证与演示 |
| --- | --- | --- |
| ReAct 与 Provider | `agent/AgentLoop`、`model/HttpModelProvider` | `AgentCommandsTest`、`ProviderProtocolTest`；read-and-search mock 演示实际执行文件读取与检索 |
| 工具与执行策略 | `tool/ToolRegistry`、`tool/BuiltinTools`、`policy/ExecutionPolicy` | `AgentBoundaryTest`；越界、未授权、重复调用编号前置拒绝；固定命令白名单 |
| Plan-and-Execute | `plan/ModelPlanner`、`PlanDefinition`、`PlanRunner` | `PlanCommandsTest` / `PlanRunnerTest`；屏障验证并行，写 Worker 串行，返修保留输出且不重放写操作 |
| 上下文与记忆 | `memory/ContextCompressor`、`MemoryStore`、`ProjectDatabase` | 工具配对、待完成调用保护、数据库重开与项目隔离；memory save / recall 和 agent --session |
| 代码库 RAG | `rag/CodeChunker`、`CodeIndex`、`Embeddings` | 增改删、失败保留旧版本、来源/过期/降级、受保护缓存清理；index / search |
| MCP | `mcp/McpClient`、两类 Transport | 握手、分页工具发现、命名空间、schema 校验、权限拒绝、取消及资源释放 |
| 原命令兼容 | `execution/FlowRunner`、原命令类 | `FlowTrailTest` 的 30 项行为用例与解压发行包冒烟 |

## 可复现操作

```sh
java -jar target/flowtrail.jar run examples/hello.json --json
java -jar target/flowtrail.jar index --project examples/codebase --json
java -jar target/flowtrail.jar agent "分析退款入口" --project examples/codebase --provider mock --script examples/agent/read-and-search.json --session refund-review --json
java -jar target/flowtrail.jar plan examples/agent/plan.json --project examples/codebase --provider mock --parallelism 2 --json
java -jar target/flowtrail.jar memory save "退款通过 requestKey 复用结果" --project examples/codebase --source src/demo/OrderService.java --json
java -jar target/flowtrail.jar memory recall "退款" --project examples/codebase --json
java -jar target/flowtrail.jar mcp examples/agent/mcp-stdio.json --json
```

两类 MCP、授权调用与拒绝流程见 [Agent 实现说明](agent-implementation.md)。文件修改先给出唯一匹配差异，命令要求程序和参数完全匹配白名单。Reviewer 依据成功工具轨迹和真实退出码验收；分析结论仍需结合来源检查。

## 检索实测口径

| 规模 | HYBRID warm p50 / p95 | Recall@5 / MRR@5 |
| --- | --- | --- |
| 1000 块 | 295.46 ms / 381.25 ms | 1.00 / 1.00 |
| 5000 块 | 1803.60 ms / 4179.99 ms | 1.00 / 1.00 |

以上为 2026-10-02 的合成 Java 标识符任务，50 个自动标注问题，mock-hash-v1 96 维；完整搜索包含查询向量、SQLite/文件新鲜度检查、相似度、关键词与融合。first-pass 和 warm 是同进程遍次。质量指标限定该语料和题目；面试数字应同时说明规模和口径。

原始逐题数据：[1000 块](benchmarks/retrieval-1000-mock.json)、[5000 块](benchmarks/retrieval-5000-mock.json)。复现脚本和分段计时见 [存储与检索](storage-implementation.md)，最终检查见 [验证报告](verification-0.2.md)。
