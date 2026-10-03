# Changelog

## 0.2.0 — 2026-10-03

- 新增 `agent / plan / index / search / memory / mcp`。
- ReAct、OpenAI 兼容/Ollama 流式协议、工具策略、审批、预算与审计。
- 独立 Plan DAG、只读并行、写操作串行、结果审查与有限返修。
- SQLite 项目记忆和会话，保留工具调用/结果配对的上下文压缩。
- JavaParser 代码分块、增量索引、Embedding 与混合排序、来源和降级状态。
- MCP stdio 与 Streamable HTTP、本地协议演示与权限检查。
- 原 `init / validate / run / doctor` 及顺序任务格式保持兼容。

## 0.1.0

picocli 顺序任务执行器、TEXT/HTTP、结构化 JSON 输出与独立发行包。
