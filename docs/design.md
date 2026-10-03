# FlowTrail CLI v0.1 设计

本文保留 0.1 的顺序任务设计；0.2 的 Agent、Plan 和 MCP 见 [实现说明](agent-implementation.md)，记忆与检索见 [存储说明](storage-implementation.md)。

目标：用 Java 21 构建独立的顺序任务 CLI，克隆后无需账号、数据库或外部服务即可演示。

- `init [FILE]` 生成任务，默认 flow.json，不覆盖已有文件。
- `validate FILE` 只做本地校验，不发起网络请求。
- `run FILE` 校验完整文件再执行，支持 text 与 http 步骤，失败即停止。
- `doctor` 检查 Java 版本和当前目录可写性。
- 子命令均支持 `--json`；成功 stdout 为单个 JSON 文档，失败 stderr 为单个 JSON 错误；帮助和版本是文本。
- 默认输出可读文本，运行时逐步输出。JSON 模式在完成时一次输出结果。
- 退出码：0 成功，1 执行或 I/O 失败，2 参数或定义无效，130 执行线程中断。

任务顶层为 `name`、`steps`；步骤 id 为字母开头的字母数字下划线。
text 步骤含 text；http 步骤含 url、可选 method（GET/POST）、headers、body、timeoutSeconds（默认 10，范围 1–300）。
HTTP 仅允许 http/https，拒绝 URL 内嵌账号密码，不自动跳转或重试，非 2xx 失败。
超时覆盖完整响应正文；动态 URL 静态阶段只校验引用，在执行前校验最终地址。
`${stepId.output}` 引用此前 text 输出或 HTTP 原始响应正文，不递归插值，不支持任意表达式。
不允许重复 id、前向引用、未知字段、未知类型或字段类型隐式转换。
CLI 不回显请求头及原始异常内容；任务输出本身可能包含敏感数据，由使用者控制。

结构：command 负责参数与输出；definition 负责解析、校验与引用；execution 负责顺序调度；step 负责 HTTP；output 负责输出协议。
Java record 表达不可变数据，不引入仅用于消除 record 已消除的样板代码的依赖。
首版不提供并行 DAG、Shell 执行、重试、插件加载、远程平台适配或 AI 调用。
