# Agent、Plan 与 MCP 实现说明

FlowTrail CLI 0.2.0 在原有顺序任务执行器之外增加了独立的 Coding Agent。`init / validate / run / doctor` 继续使用原来的定义、执行路径、退出码与 JSON 输出约定。

## 可复现的本地演示

以下命令从仓库根目录执行；先运行 `mvn clean verify` 生成 `target/flowtrail.jar`。

```shell
java -jar target/flowtrail.jar run examples/hello.json --json
java -jar target/flowtrail.jar index --project examples/codebase --json
java -jar target/flowtrail.jar agent "分析退款入口和Service" --project examples/codebase --provider mock --script examples/agent/read-and-search.json --session refund-review --json
java -jar target/flowtrail.jar plan examples/agent/plan.json --project examples/codebase --provider mock --parallelism 2 --json
java -jar target/flowtrail.jar memory --project examples/codebase save "退款示例采用进程内幂等" --source src/demo/OrderService.java
java -jar target/flowtrail.jar agent "退款示例" --project examples/codebase --provider mock --session refund-review --recall --json
```

`mock` 是固定响应序列；本地 Java 文件读取、索引查询、工具策略、DAG 调度和 SQLite 保存都执行实际代码。固定回复用于可重复演示，不作为模型质量指标。

MCP 的 stdio 演示使用独立子进程：

```shell
java -jar target/flowtrail.jar mcp examples/agent/mcp-stdio.json --json
java -jar target/flowtrail.jar mcp examples/agent/mcp-stdio.json --tool demo__echo --arguments '{"text":"hello"}' --json
java -jar target/flowtrail.jar mcp examples/agent/mcp-stdio.json --tool demo__echo --arguments '{"text":"hello"}' --allow-remote --json
```

第二条命令返回 `approval_required`，不会调用远程工具。第三条显式授权后执行。Windows PowerShell 对原生命令的引号传递规则因版本不同可能不同，可先用无参数的发现命令或由 `agent --mcp` 调用工具。

HTTP 演示在一个终端启动本地服务，再在另一个终端发现或调用：

```shell
java -cp target/flowtrail.jar examples/McpDemoServer.java --http 8088
java -jar target/flowtrail.jar mcp examples/agent/mcp-http.json --json
```

`examples/McpDemoServer.java` 是两种传输共用的本地协议测试服务，HTTP 仅监听回环地址。`--mcp CONFIG` 可把已配置服务的工具加入 Agent；所有远程工具仍经过统一授权策略。

## 模型与 Agent 循环

- `ModelProvider` 统一消息、工具定义、文本片段和工具调用。`HttpModelProvider` 分别解析 OpenAI SSE 与 Ollama NDJSON；OpenAI 参数分片按调用索引组装，Ollama 使用对象参数并以 `tool_name` 回填结果。
- `AgentLoop` 执行请求、校验、策略检查、工具执行、结果回填循环；只记录可观察的工具轨迹，不获取隐藏思维链。
- 默认最多 12 轮、每轮最多 32 个工具调用；同一工具和参数累计失败 3 次停止。重复或空调用编号会在本轮任何工具执行前被拒绝。
- `--input-budget` 默认 24000，使用保守字符估算；模型输出上限 4096。上下文在请求前压缩，保留系统约束、当前目标和未完成调用，工具请求与结果成组处理。它不是精确 tokenizer。
- 工具默认超时 30 秒，默认输出最多 16000 字符；`read_file` 支持按行再次读取。模型和工具失败不会静默切换为 mock。
- `--provider openai --model MODEL --base-url URL` 或 `--provider ollama --model MODEL` 选择适配器；密钥通过 `OPENAI_API_KEY` / `OLLAMA_API_KEY` 环境变量读取。
- `--session ID` 显式保存并恢复项目内 SQLite 会话；`--recall` 召回用户显式保存的事实，作为资料加入上下文，不生成执行权限。

## 工具和执行约束

`read_file / list_files / search_code / apply_patch / run_command` 共享 `ToolRegistry`。每个工具声明 schema、副作用类别和执行逻辑。Plan Worker 使用同一个注册表，并额外受任务 `tools` 白名单约束。

`ExecutionPolicy` 把路径限制在项目真实路径内，同时检查用户路径与符号链接解析后的路径；`.git / .flowtrail / .aws / .ssh / .codex / .agents`、`.env*`、私钥文件扩展名受到保护。策略拒绝优先于授权。

`apply_patch` 采用一次精确文本替换：`path / before / after`。预期文本必须唯一匹配，空 `before` 只允许新文件或空文件。执行前生成 diff；`--allow-write` 为本次命令提供显式授权。写入使用同目录临时文件与原子替换。

`run_command` 接收程序和参数数组，只执行 `--command-allowlist FILE` 中完全匹配的一项。文件格式示例：`[["java","-version"]]`。不隐式调用 Shell。输出包括真实退出码；超时或中断会终止子进程及其后代。

以上属于应用执行约束，不是操作系统沙箱。获得授权的程序仍具有当前操作系统用户的权限；面对外部进程同时修改目录的场景，仍应使用容器或系统权限隔离。

`.flowtrail/audit.jsonl` 只保存时间、会话、工具名、结果状态和耗时，不保存工具参数、完整文件内容、模型密钥或远程响应。会话持久化是独立的显式功能。

## Plan 的三个角色

`ModelPlanner.create()` 把目标转换为 Plan JSON，并立即通过程序校验。`plan --generate-goal GOAL` 可以调用该入口；也可直接执行用户提供的 Plan 文件。

Plan 版本 1 包含 `tasks`；每个任务声明 `id / goal / dependsOn / tools / expectedOutput`。可选 `requiredTools` 声明必须观察到的成功工具证据，`script` 选择该 Worker 的 mock 序列。引用 `${id.output}` 只允许指向祖先，结果替换只解释一次。循环、未知依赖和非祖先引用在工具初始化前拒绝。

`PlanRunner.Worker` 使用 `AgentLoop` 完成具体任务；有界线程池并行运行已就绪的只读任务，批次用 `CompletableFuture` 汇合。默认并行度 2、最大 8，计划最多 30 个任务。写文件、命令及远程工具所属 Worker 串行运行；工作线程不会等待同一线程池中的后继任务。

`PlanRunner.Reviewer` 是结构化证据检查器：要求 Worker 成功，检查 `requiredTools` 是否有成功轨迹；要求 `run_command` 时进一步检查实际输出中的退出码为 0。它不把模型自称“测试通过”作为证据，也不自动证明分析结论的语义正确性。

只读任务收到 Reviewer 反馈后最多返修 `--max-repairs` 次（默认 1、上限 5）。可能执行过副作用的 Worker 不会自动重放。`--repair-plan FILE` 提供显式新计划：新依赖重新校验，已执行任务定义必须完全保留，使用新任务 ID 描述后续验证或修复。返回结果保留每次 Worker 输出和 Reviewer 反馈。

## MCP 协议与边界

客户端协商 `2025-11-25`，完成 initialize、`notifications/initialized`、工具分页发现和调用。工具名为 `服务名__工具名`，重名注册拒绝；外部声明的只读提示不能降低本地审批等级。

stdio 使用专用标准输入/输出通道，忽略子进程 stderr，支持 ping 并拒绝未声明的客户端能力；退出和超时清理进程。Streamable HTTP 支持 JSON 与 POST SSE 响应、会话 ID、协议版本头和 DELETE 关闭会话。超时尝试发送取消通知；连接丢失时失败并停止，不自动重放结果不明的调用。

当前客户端聚焦工具能力；不提供 OAuth、资源订阅、采样、持久 SSE 游标恢复或任意 JSON Schema 关键字。参数校验支持对象、数组、基本类型、required、enum、长度与数值上下界；遇到不支持的 schema 关键字拒绝执行。MCP 连接配置由命令行用户提供，模型不能自行启动服务或改变配置。

协议参考：[MCP 传输规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[MCP 生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[Ollama 工具调用](https://docs.ollama.com/capabilities/tool-calling)。

## 本地测试证据

新增测试先验证命令尚不存在或暴露的边界缺陷，再实现对应能力。2026-10-03 的完整 `mvn clean verify` 通过 75 个用例，0 失败、0 错误、0 跳过，包含统一格式检查和发行打包；最终记录见 `verification-0.2.md`。

| 测试 | 实际断言 |
| --- | --- |
| FlowTrailTest | 30 项原命令行为、离线顺序执行、JSON、HTTP 超时与错误退出码 |
| AgentCommandsTest | 工具回填、拒绝写入零副作用、补丁预览、越界拒绝、预算退出、检索与会话重开 |
| AgentBoundaryTest | 符号链接别名保护、调用编号重复前置拒绝、取消停止新调用、上下文与参数类型边界 |
| ProviderProtocolTest | 本地 OpenAI SSE 分片、本地 Ollama NDJSON 回填、HTTP 错误不回退 |
| PlanCommandsTest | 非法 DAG 拒绝、屏障证明两个只读分支重叠、汇合一次、拒绝后写任务不重放 |
| PlanRunnerTest | 写 Worker 峰值并发为 1、返修次数上限、显式修订保留完成结果 |
| McpCommandsTest | 两种传输握手/发现/调用、HTTP 头与会话关闭、审批阻止调用、stdio 超时清理 |
| McpSchemaTest / McpCancellationTest | additionalProperties 子 schema 递归校验、取消通知阻塞仍按截止时间清理 |

性能指标与检索质量由独立检索测评记录给出，不从协议测试耗时推导吞吐量。
