# 固定代码检索示例 / Fixed Code Retrieval Fixture

这是用于 Agent 和代码检索的最小 Java 语料，不是生产支付系统。OrderService 展示进程内请求键复用，InventoryService 展示库存 CAS，OrderController 展示输入检查。

This is a small deterministic Java corpus for Agent and retrieval demonstrations. It has no network calls and makes no production payment guarantees.

```powershell
java -jar target/flowtrail.jar index --project examples/codebase --json
java -jar target/flowtrail.jar search "refundOrder 幂等" --project examples/codebase --json
```

默认 `mock-hash-v1` 是离线哈希向量，用于验证工程链路；不代表真实语义 Embedding。真实调用需明确选择 `--embedding-provider openai` 或 `ollama` 并配置环境变量。
