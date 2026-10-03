# 项目记忆、上下文与代码检索

## 运行入口

```powershell
java -jar target/flowtrail.jar memory save "订单模块通过 requestKey 复用退款结果" --source src/demo/OrderService.java --project examples/codebase --json
java -jar target/flowtrail.jar memory recall "订单" --project examples/codebase --json
java -jar target/flowtrail.jar memory list --project examples/codebase --json
java -jar target/flowtrail.jar memory delete MEMORY_ID --project examples/codebase --json

java -jar target/flowtrail.jar index --project examples/codebase --json
java -jar target/flowtrail.jar search "refundOrder 幂等" --project examples/codebase --json
java -jar target/flowtrail.jar search "reserveStock" --project examples/codebase --mode KEYWORD --json
```

新命令的 `--json` 输出一个完整 JSON 对象。默认 `mock-hash-v1` 是 96 维离线哈希向量，输出包含 `mock: true`；它用于可重复的工程验证。显式 `--embedding-provider openai` 或 `ollama` 选择 HTTP 适配器，`--embedding-model`、`--embedding-base-url` 指定模型和服务地址。OpenAI 适配器从 `OPENAI_API_KEY` 或 `FLOWTRAIL_API_KEY` 读取凭据；向量维度由 `FLOWTRAIL_EMBEDDING_DIMENSIONS` 配置，OpenAI 默认 1536，Ollama 默认 768。密钥不进入 SQLite 或返回对象。

## 记忆和会话

`MemoryStore` 使用 SQLite JDBC，将事实、来源、时间及会话消息 JSON、摘要保存到项目的 `.flowtrail/storage.sqlite`。每次操作使用短连接和预编译 SQL；数据库位于项目内部，表内再以项目真实路径隔离作用域。存储目录和数据库路径拒绝符号链接越界。

事实通过 `save` 显式写入，`list/recall/delete` 管理；`saveSession/loadSession` 支持进程重启后继续读取。召回按查询词与事实/来源的文本匹配排序，记忆只提供上下文资料，不授予工具权限。

`ContextCompressor` 在输入预算内保留系统约束、当前用户目标和未完成的工具调用。一个 assistant 的 tool_calls 与紧随其后的 tool 结果属于同一组，完整保留或一起压缩；孤立结果和不匹配的调用 ID 被拒绝。较早消息形成带数据标签的摘录摘要，不添加新的 system 指令。受保护内容本身超过预算时明确失败。

预算使用序列化字符数与消息开销的保守估算，调用方需预留模型输出及新工具结果空间。这个估计不是供应商的精确 tokenizer。摘要是确定性的摘录，便于重现和审计。

## 索引与检索

- JavaParser 3.26.3 以 Java 21 语法分析类型、方法、构造器和成员，保留文件、符号、起止行号；普通文本按行分块。单块最多 6,000 字符，超长单行继续切分。
- 单文件上限 1 MB。目录扫描剪枝排除 `.git`、`.flowtrail`、构建产物、缓存、依赖目录；仅索引允许的文本扩展名，并排除常见秘密文件名。`--exclude` 可重复传入项目内的敏感目录或文件前缀。
- SQLite 保存文件 SHA-256、块内容、来源、JSON 向量和过期标记。索引元数据保存模型标识、维度和分块版本，不兼容时拒绝读取和增量更新，使用显式 `index --rebuild` 重建。
- 更新先在事务外完成文件读取、解析和全部 Embedding，再在短事务中替换该文件的旧块。任一候选生成失败保留上一版已提交结果并标为过期；文件删除同步清理。查询还检查磁盘文件哈希，能报告尚未更新的修改。
- 标识符、camelCase、中文 bigram 提供关键词匹配；余弦向量得分与关键词得分按 0.6 / 0.4 融合，方法块权重 1.05，同文件最多 3 条。当前分词实现无需外部词典。
- 查询 Embedding 失败时返回关键词结果，`degraded/degradationReason` 明确标记降级；没有静默更换模型。来源包含 `path/symbol/startLine/endLine`，同时返回是否过期、模型、模式和分段耗时。

HTTP 适配器覆盖 OpenAI `/embeddings` 和 Ollama `/api/embed`，校验状态码、响应大小、维度、有限数值和非零向量。异步响应订阅器限制为 8 MB，全响应期限默认 30 秒，超时会取消底层请求；这同时覆盖已经返回响应头但正文停止传输的情况。本地协议测试使用真实 JDK HTTP 服务收发请求。

## 测试和测评

存储模块本地验证记录：2026-10-02，Java 21.0.1、Windows amd64。`storage` 包 13 项测试通过，涵盖数据库重开/隔离、SQL 内容作为数据、会话校验、工具配对压缩、待完成调用保护、索引增改删、失败版本保留、模型不匹配、未索引修改的过期提示、同文件上限、HTTP 协议、响应正文停滞超时、单 JSON 命令输出与指标计算。

```powershell
mvn test -Dtest=MemoryStoreTest,ContextCompressorTest,CodeIndexTest,EmbeddingProtocolTest,StorageCommandsTest,RetrievalEvaluationTest
```

生成合成语料和复现实测：

```powershell
./scripts/generate-retrieval-corpus.ps1 -Destination .cache/benchmark-1000 -Chunks 1000
./scripts/evaluate-retrieval.ps1 -Project .cache/benchmark-1000 -Questions .cache/benchmark-1000-questions.json -Report docs/benchmarks/retrieval-1000-mock.json -WarmRepeats 1

./scripts/generate-retrieval-corpus.ps1 -Destination .cache/benchmark-5000 -Chunks 5000
./scripts/evaluate-retrieval.ps1 -Project .cache/benchmark-5000 -Questions .cache/benchmark-5000-questions.json -Report docs/benchmarks/retrieval-5000-mock.json -WarmRepeats 1
```

生成器只写入新的目标目录，不覆盖已有语料。每个 Java 文件形成类型和方法两个块；50 个标识符问题带自动生成的文件相关性标签，题目文件保存在语料目录之外，避免答案进入索引。测评分别运行 HYBRID、KEYWORD、VECTOR，输出 Recall@5、MRR@5、每阶段 p50/p95，以及全部逐题原始结果。

原始记录位于 `docs/benchmarks/`，以 `mock: true` 标明合成语料验证。计时是进程内搜索；`first-pass` 是该模式的第一遍，`warm` 是后续遍次。向量阶段计时包含 SQLite 读取、文件新鲜度检查和余弦计算；完整耗时还包括查询向量、关键词与融合排序。指标衡量该固定标识符任务，可通过脚本替换为其他标注语料。

## 代码入口

| 能力 | 入口 |
| --- | --- |
| SQLite 作用域与路径校验 | `memory/ProjectDatabase.java` |
| 事实和会话 | `memory/MemoryStore.java` |
| 配对上下文压缩 | `memory/ContextCompressor.java` |
| Java 语义块 | `rag/CodeChunker.java` |
| 增量索引、融合检索 | `rag/CodeIndex.java` |
| HTTP / mock 向量 | `rag/Embeddings.java` |
| 测评与原始记录 | `rag/RetrievalEvaluation.java` |
| CLI | `command/StorageCommands.java` |

索引采用 SQLite 和进程内扫描，适合本地小规模代码库。`.flowtrail/` 是私有项目状态，发行和版本管理排除该目录。
