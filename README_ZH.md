# FlowTrail CLI

### 面向本地代码库的 Java Coding Agent

**在命令行中检索代码、调用工具，并用有界 Plan DAG 执行任务。** 项目记忆、工具权限和两类 MCP 客户端配合可复现演示，帮助追踪每一步的来源、结果与验证证据。

[English](README.md) | 中文

[快速开始](#快速开始) · [命令](#命令) · [Agent 演示](#agent-与代码库演示) · [验证](#验证) · [更新记录](CHANGELOG.md)

![FlowTrail CLI 实现架构：Agent 循环、执行策略、工具、Plan、记忆与代码检索](docs/images/architecture.svg)

*实现架构示意。模型响应经参数与权限检查后调用工具；Plan、项目记忆与代码检索复用同一条执行路径。*

Windows 可双击根目录 `start.bat`：自动查找 Java 21+。通过菜单运行离线示例、环境诊断或查看帮助。缺少 JAR 时会提示先构建；启动失败保留错误信息。`start.bat -Check` 仅检查启动环境。

0.2.0 将 Agent 工程和确定性任务执行放在独立命令中：用 `agent` 分析代码和调用工具，用 `plan` 执行带依赖与验证证据的任务，用 `run` 复现固定的文本与 HTTP 步骤。

默认 Agent 与 Embedding 演示采用显式 `mock`；项目状态自动保存到项目内 SQLite。旧任务示例离线运行，HTTP 和 MCP 示例提供本地服务。

```text
$ flowtrail run examples/hello.json
[greeting] 你好，FlowTrail！
[summary] 上一阶段输出：你好，FlowTrail！
Completed 2 steps.
```

## 快速开始

源码构建需要 JDK 21+ 和 Maven 3.8.5+。请先确认 `java -version` 与 `mvn -version` 都指向 JDK 21 或更新版本；可通过 `JAVA_HOME` 指定 JDK。

在仓库根目录执行：

```sh
mvn clean verify
java -jar target/flowtrail.jar --help
java -jar target/flowtrail.jar doctor
java -jar target/flowtrail.jar run examples/hello.json
```

也可使用启动脚本：

```powershell
# Windows PowerShell
.\bin\flowtrail.ps1 run examples/hello.json --json
```

```sh
# Linux / macOS
sh bin/flowtrail.sh run examples/hello.json --json
```

构建同时生成 `target/flowtrail-dist.zip`。解压后可直接使用其中的 `bin/` 启动器，只需 JDK 21+，无需 Maven。也可以 `java -jar lib/flowtrail.jar --help`。

## 命令

| 命令 | 用途 |
| --- | --- |
| `init [FILE]` | 创建离线示例，默认写入 `flow.json`，拒绝覆盖已有文件 |
| `validate FILE` | 校验定义、步骤 ID、参数和引用，不发送请求 |
| `run FILE` | 校验完整定义后顺序执行，任一步失败立即停止 |
| `doctor` | 检查当前 Java 版本和工作目录可写性 |
| `agent GOAL` | 模型请求、参数校验、策略检查、工具执行与结果回填 |
| `plan [FILE]` | 校验 Plan DAG，并行只读 Worker、串行写 Worker、证据审查与有限返修 |
| `index` | Java 语义分块、文件哈希增量更新、Embedding 和 SQLite 索引 |
| `search QUERY` | 关键词、向量或融合检索，返回文件、符号、行号、过期与降级标记 |
| `memory save/list/recall/delete` | 管理项目事实及其来源 |
| `mcp CONFIG` | 初始化、发现或调用带服务命名空间的 MCP 工具 |

每个子命令都支持 `--help` 与 `--json`，版本信息使用 `flowtrail --version`。`--json` 放在子命令后面。

## Agent 与代码库演示

```sh
java -jar target/flowtrail.jar index --project examples/codebase --json
java -jar target/flowtrail.jar search "refundOrder requestKey" --project examples/codebase --json
java -jar target/flowtrail.jar agent "分析退款入口与幂等实现" --project examples/codebase --provider mock --script examples/agent/read-and-search.json --session refund-review --json
java -jar target/flowtrail.jar plan examples/agent/plan.json --project examples/codebase --provider mock --parallelism 2 --json
java -jar target/flowtrail.jar memory save "退款以 requestKey 复用结果" --project examples/codebase --source src/demo/OrderService.java --json
java -jar target/flowtrail.jar memory recall "退款" --project examples/codebase --json
java -jar target/flowtrail.jar mcp examples/agent/mcp-stdio.json --json
```

ModelProvider 提供 OpenAI SSE、Ollama NDJSON 和 mock；Embedding 提供 OpenAI、Ollama 与离线哈希向量。选择对应 provider、model、base-url，凭据从环境变量读取。工具参数完整组装并校验后才执行，失败不会静默切换模式。

内置 `read_file / list_files / search_code / apply_patch / run_command`。路径限制在项目真实目录内，敏感目录和符号链接目标统一校验；索引与旧缓存查询也遵守该策略。写文件需要 `--allow-write`，远程工具需要 `--allow-remote`；命令仅允许用户给出的程序/参数精确白名单。`apply_patch` 生成差异后做唯一文本替换。审计不保存完整参数与文件内容。

工具调用与结果成组压缩，输入预算采用保守估计。SQLite 保存项目事实、会话、索引版本与来源。Plan 的 Planner 生成并校验计划，Worker 复用 AgentLoop，Reviewer 检查真实工具和命令退出码；已执行写任务在返修时保留输出且不自动重放。

详见 [Agent / Plan / MCP](docs/agent-implementation.md)、[记忆与代码检索](docs/storage-implementation.md)、[简历能力与证据](docs/resume-evidence.md)。

## 定义任务

```json
{
  "name": "greeting",
  "steps": [
    { "id": "first", "type": "text", "text": "Hello" },
    { "id": "second", "type": "text", "text": "${first.output}, FlowTrail!" }
  ]
}
```

- `name` 为非空字符串，`steps` 为非空数组；文件大小不超过 1 MiB。
- 步骤 ID 必须唯一，以 ASCII 字母开头，随后仅允许字母、数字和下划线。
- `text` 步骤需要字符串 `text`。
- `${stepId.output}` 只能引用前面步骤的输出，可用于 `text`、HTTP `url`、`body` 和请求头值。
- 插值只进行一次；输出中包含 `${...}` 不会作为表达式再次执行。
- HTTP 输出是 UTF-8 解码后的原始响应正文；首版不支持 JSONPath 或自动 URL 编码。
- 未知字段、前向引用、错误字段类型和重复 JSON 属性均报错。

HTTP 步骤示例：

```json
{
  "id": "request",
  "type": "http",
  "url": "http://127.0.0.1:8099/echo",
  "method": "POST",
  "headers": { "Content-Type": "text/plain; charset=utf-8" },
  "body": "${first.output}",
  "timeoutSeconds": 5
}
```

`method` 默认 `GET`，仅支持 `GET` 和 `POST`；GET 不允许 `body`。超时默认 10 秒，范围 1–300 秒，覆盖响应正文读取。非 2xx 状态报错，不自动重试或跟随重定向。

地址仅支持 HTTP/HTTPS，禁止内嵌用户名、密码与 URL fragment。含引用的地址在执行时再次检查最终结果；`validate` 对动态地址只检查引用是否合法。请求头名称不区分大小写，不允许重复或设置由客户端管理的 `Host`、`Content-Length` 等头。

任务文件是可执行操作的描述。请只运行自己信任的定义；HTTP 步骤可以访问本机网络，POST 也可能改变目标服务状态。响应正文会保留在内存中，首版面向小型文本 API，不用于大文件下载。程序不打印请求头或失败响应正文，但成功的任务输出仍可能包含敏感内容。

## 本地 HTTP 演示

终端一启动模拟服务，只监听 `127.0.0.1`：

```sh
java --source 21 examples/MockServer.java
```

终端二运行：

```sh
java -jar target/flowtrail.jar validate examples/http.json
java -jar target/flowtrail.jar run examples/http.json
```

模拟服务会将 POST 正文原样返回，最后一步输出 `HTTP response: Hello from FlowTrail`。按 Ctrl+C 停止服务。端口 8099 被占用时，可给模拟服务传入其他端口，并同步修改示例 URL。

## 输出和退出码

普通模式逐步打印结果。`--json` 模式成功时 stdout 只有一个 JSON 文档：

```json
{"ok":true,"name":"greeting","steps":[{"id":"first","output":"Hello"},{"id":"second","output":"Hello, FlowTrail!"}]}
```

失败时 stdout 为空，stderr 输出一个错误对象，已执行步骤的输出不会混入 JSON：

```json
{"ok":false,"exitCode":2,"error":"References must point to an earlier step's output."}
```

| 退出码 | 含义 |
| --- | --- |
| 0 | 成功 |
| 1 | 文件 I/O、网络、超时或 HTTP 状态失败 |
| 2 | 参数或任务定义无效 |
| 130 | 执行线程中断 |

帮助和版本始终输出文本。操作系统直接终止进程时，不保证输出 JSON 错误。

## 设计与学习重点

```mermaid
flowchart LR
  CLI[命令与参数] --> Loader[JSON 解析与全量校验]
  Loader --> Runner[顺序执行器]
  Runner --> Text[文本与引用替换]
  Runner --> HTTP[JDK HTTP Client]
  Text --> Result[结果与退出码]
  HTTP --> Result
```

- **CLI 设计**：picocli 子命令、帮助、stdout/stderr 分离与退出码。
- **Java 工程化**：record 建模、职责拆分、Maven 构建与可执行 JAR。
- **网络编程**：本地 HTTP 集成测试、总超时、异步取消与资源释放。
- **质量验证**：先校验后执行、错误分支测试和发布包冒烟测试。

见 [设计说明](docs/design.md)、[实现复盘](docs/learning-notes.md) 和 [实际终端演示记录](docs/demo.cast)。演示为 asciicast v2 格式，可用 asciinema 播放；无需播放工具也能按快速开始复现。初始版本在 AI 编程助手协助下实现；功能和验证记录以仓库内容为准，后续学习可围绕独立复现、修改和解释设计展开。

旧 `run` 继续顺序执行；新增 `plan` 管理独立 DAG。检索使用进程内余弦计算，适合本地小规模代码库；MCP 聚焦工具能力，执行策略属于应用约束。当前不提供插件市场或操作系统级沙箱。

## 验证

```sh
mvn clean verify
```

```powershell
# 验证解压后的发行包和启动器
.\scripts\smoke.ps1
```

自动化测试使用临时目录与随机端口，不依赖公网 API。

本次验证记录见 [验证报告](docs/verification-0.2.md)。1,000 / 5,000 代码块、50 个标注问题的逐题数据保存在 [检索测评](docs/benchmarks/)；模型、阶段计时与测量口径均随结果记录。

## 许可

本项目代码使用 [MIT](LICENSE)。运行时依赖的许可与署名信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 `licenses/`。
