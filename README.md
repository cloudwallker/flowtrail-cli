# FlowTrail CLI

### A Java coding agent for local codebases

**Search code, call tools, and execute bounded task plans from your terminal.** Project memory, execution policies, and two MCP transports provide traceable results and repeatable demonstrations alongside a deterministic JSON task runner.

English | [中文](README_ZH.md)

[Quick Start](#quick-start) · [Commands](#commands) · [Agent Demo](#agent-and-codebase-demo) · [Verification](#verification) · [Changelog](CHANGELOG.md)

![FlowTrail CLI implementation architecture: agent loop, execution policies, tools, plans, memory and code retrieval](docs/images/architecture.svg)

*Implementation architecture. Model responses pass argument and permission checks before tools run; plans, project memory, and code retrieval share the execution path.*

## Features

- **Coding agent:** OpenAI SSE, Ollama NDJSON, and scripted mock providers; validated tool calls, bounded rounds, deadlines, and context budgets.
- **Task plans:** validated DAGs, parallel read-only Workers, serial write Workers, evidence-based review, and bounded repair that preserves completed writes.
- **Project context:** SQLite facts and sessions, paired tool-call compression, JavaParser chunks, incremental indexing, and keyword/vector/hybrid retrieval with source locations.
- **MCP tools:** stdio and Streamable HTTP clients with namespaced discovery, schema checks, explicit approval, cancellation, and resource cleanup.
- **Repeatable tasks:** the original `run` command validates and executes JSON text/HTTP steps in order, with stable JSON output and exit codes.

## Quick Start

Building from source requires **JDK 21+ and Maven 3.8.5+**. Check that both `java -version` and `mvn -version` use a suitable JDK; set `JAVA_HOME` when needed.

From the repository root:

```sh
mvn clean verify
java -jar target/flowtrail.jar --help
java -jar target/flowtrail.jar doctor
java -jar target/flowtrail.jar run examples/hello.json
```

Expected task output:

```text
[greeting] 你好，FlowTrail！
[summary] 上一阶段输出：你好，FlowTrail！
Completed 2 steps.
```

On Windows, double-click `start.bat` to find Java 21+, run an offline example, check the environment, or view help. The launcher retains startup errors and asks you to build if the JAR is missing. `start.bat -Check` only checks the environment.

The build creates `target/flowtrail-dist.zip`. Extract it and use the included launchers with JDK 21+; Maven is only needed for the source build. You can also run `java -jar lib/flowtrail.jar --help` from the extracted directory.

```powershell
# Windows PowerShell, in the source repository
.\bin\flowtrail.ps1 run examples/hello.json --json
```

```sh
# Linux / macOS, in the source repository
sh bin/flowtrail.sh run examples/hello.json --json
```

## Commands

| Command | Purpose |
| --- | --- |
| `init [FILE]` | Create an offline example; defaults to `flow.json` and refuses to overwrite a file |
| `validate FILE` | Validate a task, step IDs, arguments, and references without sending requests |
| `run FILE` | Validate the complete task, execute steps sequentially, and stop on failure |
| `doctor` | Check the Java version and working-directory permissions |
| `agent GOAL` | Request a model, validate calls, check policies, execute tools, and return results to the model |
| `plan [FILE]` | Run a validated Plan DAG with bounded concurrency, evidence review, and repair |
| `index` | Build Java/text chunks, update file hashes, and store embeddings in SQLite |
| `search QUERY` | Retrieve source locations with keyword, vector, or hybrid search and stale/degradation flags |
| `memory save/list/recall/delete` | Manage project facts and their sources |
| `mcp CONFIG` | Initialize, discover, or call tools in a service namespace |

Subcommands support `--help` and `--json`; put `--json` after the subcommand. Use `flowtrail --version` for the version. Help and version output are always text.

## Agent and Codebase Demo

The included codebase demonstrates order handling and idempotent refunds. These commands use explicit mock model responses and offline hash embeddings while running the actual file tools, index, scheduler, and SQLite storage:

```sh
java -jar target/flowtrail.jar index --project examples/codebase --json
java -jar target/flowtrail.jar search "refundOrder requestKey" --project examples/codebase --json
java -jar target/flowtrail.jar agent "分析退款入口与幂等实现" --project examples/codebase --provider mock --script examples/agent/read-and-search.json --session refund-review --json
java -jar target/flowtrail.jar plan examples/agent/plan.json --project examples/codebase --provider mock --parallelism 2 --json
java -jar target/flowtrail.jar memory save "退款以 requestKey 复用结果" --project examples/codebase --source src/demo/OrderService.java --json
java -jar target/flowtrail.jar memory recall "退款" --project examples/codebase --json
java -jar target/flowtrail.jar mcp examples/agent/mcp-stdio.json --json
```

Select a model adapter with `--provider openai` or `--provider ollama`, then specify `--model` and `--base-url` as appropriate. Credentials come from environment variables. Embedding adapters support OpenAI, Ollama, and offline hash vectors; provider failures never silently switch to mock.

Built-in tools are `read_file`, `list_files`, `search_code`, `apply_patch`, and `run_command`. Project paths and resolved symlink targets share a protection policy, including indexing and old-cache queries. File writes require `--allow-write`; remote calls require `--allow-remote`; commands must exactly match a user-supplied program/argument allowlist. `apply_patch` previews a diff and requires a unique text match. Audit records omit complete arguments and file contents.

Context compression keeps tool calls and results together and uses a conservative input-budget estimate. SQLite persists project facts, sessions, index versions, and source metadata. Plan Workers reuse `AgentLoop`; the Reviewer checks successful tool traces and actual command exit codes. Completed write tasks retain their results during repair and are not automatically replayed.

See [Agent / Plan / MCP](docs/agent-implementation.md), [Memory and Code Retrieval](docs/storage-implementation.md), and [Capabilities and Evidence](docs/resume-evidence.md) for implementation details and further examples.

## Define a Deterministic Task

```json
{
  "name": "greeting",
  "steps": [
    { "id": "first", "type": "text", "text": "Hello" },
    { "id": "second", "type": "text", "text": "${first.output}, FlowTrail!" }
  ]
}
```

- `name` must be a nonempty string and `steps` a nonempty array; the definition is limited to 1 MiB.
- Step IDs must be unique, begin with an ASCII letter, and contain only letters, digits, and underscores.
- A `text` step requires a string `text`. `${stepId.output}` can only reference an earlier step, in text, HTTP URLs, bodies, or header values.
- Interpolation happens once; expression-like text returned by a step is not evaluated again.
- HTTP output is the raw response body decoded as UTF-8, without JSONPath or automatic URL encoding.
- Unknown fields, forward references, invalid types, and duplicate JSON properties are rejected.

An HTTP step:

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

`method` defaults to `GET`; only GET and POST are supported, and GET cannot have a body. The timeout defaults to 10 seconds, accepts 1–300 seconds, and covers reading the response body. Non-2xx responses fail; the runner does not automatically retry or follow redirects.

URLs must use HTTP/HTTPS, without embedded credentials or fragments. Interpolated URLs are checked again at execution; `validate` checks their references. Header names are case-insensitive; duplicate names and client-managed headers such as `Host` or `Content-Length` are rejected.

Run trusted definitions: HTTP steps can access local networks, and POST can change service state. Responses stay in memory, so these tasks target small text APIs. Request headers and failed response bodies are not printed; successful outputs can still contain sensitive data.

## Local HTTP Demo

Start the loopback-only service in one terminal:

```sh
java --source 21 examples/MockServer.java
```

Run in another terminal:

```sh
java -jar target/flowtrail.jar validate examples/http.json
java -jar target/flowtrail.jar run examples/http.json
```

The service echoes the POST body; the final step prints `HTTP response: Hello from FlowTrail`. Stop it with Ctrl+C. If port 8099 is occupied, pass another port to the service and update the example URL.

## Output and Exit Codes

Text mode prints each completed step. In `--json` mode, successful stdout contains one JSON document:

```json
{"ok":true,"name":"greeting","steps":[{"id":"first","output":"Hello"},{"id":"second","output":"Hello, FlowTrail!"}]}
```

On failure, stdout stays empty and stderr contains one error object; partial step output is not mixed into it:

```json
{"ok":false,"exitCode":2,"error":"References must point to an earlier step's output."}
```

| Exit code | Meaning |
| --- | --- |
| 0 | Success |
| 1 | File I/O, network, timeout, or HTTP status failure |
| 2 | Invalid arguments or task definition |
| 130 | Execution-thread interruption |

If the operating system terminates the process directly, JSON error output is not guaranteed.

## Documentation and Scope

| Topic | Documentation |
| --- | --- |
| Agent loop, execution policies, Plan roles, and MCP | [Agent Implementation](docs/agent-implementation.md) |
| Memory, context compression, JavaParser indexing, and retrieval | [Storage Implementation](docs/storage-implementation.md) |
| Source entry points and reproducible demonstrations | [Capabilities and Evidence](docs/resume-evidence.md) |
| Local checks and test results | [0.2 Verification](docs/verification-0.2.md) |
| Recorded retrieval measurements | [Benchmark Data](docs/benchmarks/) |
| Original task-runner design and learning notes | [Design](docs/design.md) · [Learning Notes](docs/learning-notes.md) |
| Original terminal recording | [asciicast v2 Recording](docs/demo.cast) |

The original `run` remains sequential; `plan` manages a separate DAG. Retrieval uses in-process cosine scoring and targets local, small codebases. MCP support focuses on tools. Execution policies are application constraints, rather than an operating-system sandbox; there is no plugin marketplace.

The initial version was implemented with assistance from an AI coding assistant. Repository contents and verification records describe the actual implementation; learning can continue through independent reproduction, changes, and explaining the design.

## Verification

```sh
mvn clean verify
python scripts/agent_smoke.py
```

```powershell
# Check the extracted distribution and launchers
.\scripts\smoke.ps1
```

Automated tests use temporary directories and random ports, without public API dependencies. The [0.2 verification report](docs/verification-0.2.md) records 75 passing tests and seven feature demonstration groups.

[Retrieval measurements](docs/benchmarks/) cover 1,000 and 5,000 chunks with 50 automatically labelled identifier questions, using explicit mock-hash embeddings. Reports include the model, phase timings, and per-question results. These measurements describe that fixed synthetic task.

## License

Project code is licensed under [MIT](LICENSE). Runtime dependency licenses and attribution are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [licenses/](licenses/).
