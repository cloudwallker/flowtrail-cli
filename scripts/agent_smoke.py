#!/usr/bin/env python3
"""Exercise a built FlowTrail CLI JAR using only the Python standard library.

Run from any directory:
  python scripts/agent_smoke.py --jar target/flowtrail.jar --java /path/to/java
  python scripts/agent_smoke.py --output docs/agent-smoke.json

The source examples are copied into a temporary project. All model responses and
MCP servers are explicitly local fixtures; tools, storage, policies, and CLI
process boundaries are real. This script does not build the JAR.
"""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from typing import Any


PROTOCOL = "2025-11-25"
SESSION = "flowtrail-smoke-session"
REPOSITORY = Path(__file__).resolve().parents[1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def write_json(path: Path, value: Any) -> Path:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def tool_call(name: str, arguments: dict[str, Any], call_id: str = "smoke-call") -> dict[str, Any]:
    return {"toolCalls": [{"id": call_id, "name": name, "arguments": arguments}]}


class FixtureState:
    def __init__(self, counter: Path):
        self.counter = counter
        self.lock = threading.Lock()
        self.header_errors = 0

    def calls(self) -> int:
        return len(self.counter.read_text(encoding="utf-8").splitlines()) if self.counter.exists() else 0

    def respond(self, request: dict[str, Any]) -> dict[str, Any] | None:
        if "id" not in request:
            return None
        method = request.get("method")
        if method == "initialize":
            result = {
                "protocolVersion": PROTOCOL,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "flowtrail-local-smoke-fixture", "version": "1"},
            }
        elif method == "tools/list":
            result = {"tools": [{
                "name": "record_note",
                "description": "Append one fixture note to the temporary call log.",
                "inputSchema": {
                    "type": "object", "properties": {"note": {"type": "string"}},
                    "required": ["note"], "additionalProperties": False,
                },
            }]}
        elif method == "tools/call":
            params = request.get("params", {})
            require(params.get("name") == "record_note", "Unknown fixture tool")
            note = params.get("arguments", {}).get("note")
            require(isinstance(note, str), "Fixture note must be text")
            with self.lock:
                with self.counter.open("a", encoding="utf-8") as stream:
                    stream.write(json.dumps({"note": note}, ensure_ascii=False) + "\n")
            result = {"content": [{"type": "text", "text": "Recorded: " + note}], "isError": False}
        elif method == "ping":
            result = {}
        else:
            return {"jsonrpc": "2.0", "id": request["id"],
                    "error": {"code": -32601, "message": "Unsupported fixture method"}}
        return {"jsonrpc": "2.0", "id": request["id"], "result": result}


def stdio_fixture(counter: Path) -> int:
    """Invoked as the dedicated MCP child; stdout contains protocol messages only."""
    state = FixtureState(counter)
    for line in sys.stdin:
        reply = state.respond(json.loads(line))
        if reply is not None:
            print(json.dumps(reply, ensure_ascii=False), flush=True)
    return 0


@contextlib.contextmanager
def http_fixture(state: FixtureState):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_DELETE(self):
            self.send_response(204)
            self.end_headers()

        def do_POST(self):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                require(0 < length <= 65536, "Invalid fixture request length")
                request = json.loads(self.rfile.read(length))
                method = request.get("method")
                if method != "initialize":
                    valid = (self.headers.get("Mcp-Session-Id") == SESSION
                             and self.headers.get("Mcp-Protocol-Version") == PROTOCOL)
                    if not valid:
                        state.header_errors += 1
                accepted = self.headers.get("Accept", "")
                if "application/json" not in accepted or "text/event-stream" not in accepted:
                    state.header_errors += 1
                reply = state.respond(request)
                if reply is None:
                    self.send_response(202)
                    self.end_headers()
                    return
                body = json.dumps(reply, ensure_ascii=False)
                content_type = "application/json"
                if method == "tools/call":
                    body = "event: message\ndata: " + body + "\n\n"
                    content_type = "text/event-stream"
                encoded = body.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(encoded)))
                if method == "initialize":
                    self.send_header("Mcp-Session-Id", SESSION)
                self.end_headers()
                self.wfile.write(encoded)
            except (ValueError, RuntimeError, KeyError):
                self.send_error(400, "Invalid local fixture request")

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    worker = threading.Thread(target=server.serve_forever, name="mcp-smoke-http", daemon=True)
    worker.start()
    try:
        yield "http://127.0.0.1:%d/mcp" % server.server_address[1]
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=3)


class Smoke:
    def __init__(self, java: Path, jar: Path, work: Path, timeout: int):
        self.java, self.jar, self.work, self.timeout = java, jar, work, timeout
        self.project = work / "project"
        shutil.copytree(REPOSITORY / "examples" / "codebase", self.project,
                        ignore=shutil.ignore_patterns(".flowtrail", "target", ".git"))
        self.runtime = work / "java-tmp"
        self.runtime.mkdir()
        self.children: list[subprocess.Popen[str]] = []
        self.checks: list[dict[str, Any]] = []
        self.invocations = 0

    def cleanup(self) -> None:
        for child in reversed(self.children):
            self.stop(child)

    @staticmethod
    def stop(child: subprocess.Popen[str]) -> None:
        if child.poll() is None:
            if os.name == "nt":
                # Only our still-running process tree; arguments are never shell-interpreted.
                with contextlib.suppress(OSError, subprocess.TimeoutExpired):
                    subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   timeout=5, creationflags=subprocess.CREATE_NO_WINDOW)
            else:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(child.pid, signal.SIGKILL)
            with contextlib.suppress(OSError):
                child.kill()
        with contextlib.suppress(subprocess.TimeoutExpired):
            child.wait(timeout=5)

    def invoke(self, *args: Any, expected: int = 0) -> dict[str, Any]:
        command = [str(self.java), "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + str(self.runtime),
                   "-jar", str(self.jar), *map(str, args), "--json"]
        options: dict[str, Any] = {"cwd": str(REPOSITORY), "stdout": subprocess.PIPE,
                                   "stderr": subprocess.PIPE, "text": True, "encoding": "utf-8"}
        if os.name == "nt":
            options["creationflags"] = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            options["start_new_session"] = True
        child = subprocess.Popen(command, **options)
        self.children.append(child)
        self.invocations += 1
        try:
            stdout, stderr = child.communicate(timeout=self.timeout)
            require(child.returncode == expected,
                    f"{args[0]} exit {child.returncode}, expected {expected}; stderr={stderr.strip()}")
            require(bool(stdout.strip()), f"{args[0]} produced no JSON result: {stderr.strip()}")
            value = json.loads(stdout)
            require(isinstance(value, dict), "Expected exactly one JSON result object")
            require(not stderr.strip(), f"Unexpected stderr in JSON mode: {stderr.strip()}")
            return value
        finally:
            self.stop(child)

    def fixture(self, name: str, responses: list[dict[str, Any]]) -> Path:
        return write_json(self.work / (name + ".json"), responses)

    def agent(self, script: Path, *extra: Any, expected: int = 0,
              goal: str = "Inspect the local fixture") -> dict[str, Any]:
        return self.invoke("agent", goal, "--project", self.project, "--provider", "mock",
                           "--script", script, *extra, expected=expected)

    def check(self, name: str, action) -> None:
        started = time.perf_counter()
        try:
            evidence = action()
            self.checks.append({"name": name, "ok": True, "evidence": evidence,
                                "elapsedSeconds": round(time.perf_counter() - started, 3)})
        except Exception as error:
            self.checks.append({"name": name, "ok": False,
                                "error": type(error).__name__ + ": " + str(error),
                                "elapsedSeconds": round(time.perf_counter() - started, 3)})

    @staticmethod
    def trace(result: dict[str, Any], tool: str) -> dict[str, Any]:
        matches = [item["result"] for item in result.get("trace", []) if item["tool"] == tool]
        require(bool(matches), "Missing real tool trace: " + tool)
        return matches[0]

    def legacy(self) -> dict[str, Any]:
        copied = self.work / "hello.json"
        shutil.copyfile(REPOSITORY / "examples" / "hello.json", copied)
        result = self.invoke("run", copied)
        require(len(result["steps"]) == 2, "Legacy flow must execute both steps")
        greeting = read_json(copied)["steps"][0]["text"]
        require(result["steps"][0]["output"] == greeting
                and greeting in result["steps"][-1]["output"], "Legacy reference lost")
        return {"stepCount": len(result["steps"]), "output": result["steps"][-1]["output"]}

    def retrieval(self) -> dict[str, Any]:
        indexed = self.invoke("index", "--project", self.project, "--embedding-provider", "mock")
        require(indexed["chunks"] > 0 and not indexed["failures"], "Index failed")
        result = self.invoke("search", "refundOrder", "--project", self.project,
                             "--embedding-provider", "mock", "--limit", 3)
        require(result["mock"] and not result["degraded"], "Expected explicit mock hybrid search")
        require(any("OrderService.java" in item["path"] for item in result["hits"]),
                "Search did not locate OrderService")
        require(all(item["startLine"] > 0 for item in result["hits"]), "Missing source line numbers")
        return {"chunks": indexed["chunks"], "model": result["embeddingModel"],
                "sources": [{key: hit[key] for key in ("path", "symbol", "startLine", "endLine")}
                            for hit in result["hits"]]}

    def agent_and_session(self) -> dict[str, Any]:
        first = self.fixture("agent-analysis", [
            tool_call("read_file", {"path": "src/demo/OrderController.java"}, "read-controller"),
            tool_call("search_code", {"query": "refundOrder", "limit": 2}, "search-refund"),
            {"content": "Mock analysis: OrderController delegates to OrderService.refundOrder."},
        ])
        result = self.agent(first, "--session", "smoke-session", goal="SMOKE_SESSION_A refund review")
        read = self.trace(result, "read_file")
        searched = self.trace(result, "search_code")
        require(read["status"] == "ok" and "postRefund" in read["output"], "Read tool lacks source")
        require(searched["status"] == "ok" and json.loads(searched["output"])["hits"],
                "Search tool has no persisted-index hits")
        second = self.fixture("session-continue", [{"content": "Mock continuation complete."}])
        self.agent(second, "--session", "smoke-session", goal="SMOKE_SESSION_B continue review")
        with contextlib.closing(sqlite3.connect(self.project / ".flowtrail" / "storage.sqlite")) as db:
            rows = db.execute("SELECT messages FROM sessions WHERE id=?", ("smoke-session",)).fetchall()
        require(len(rows) == 1, "Expected one persisted scoped session")
        messages = json.loads(rows[0][0])
        user_messages = [message.get("content", "") for message in messages if message.get("role") == "user"]
        require(any("SMOKE_SESSION_A" in content for content in user_messages)
                and any("SMOKE_SESSION_B" in content for content in user_messages),
                "Second CLI process did not retain first session history")
        return {"provider": result["provider"], "tools": [item["tool"] for item in result["trace"]],
                "sessionProcesses": 2, "persistedMessages": len(messages), "oldAndNewGoalsRetained": True}

    def plan(self) -> dict[str, Any]:
        tasks = []
        for task_id, file in (("controller", "OrderController.java"), ("service", "OrderService.java")):
            script = self.fixture("plan-" + task_id, [
                tool_call("read_file", {"path": "src/demo/" + file}, "read-" + task_id),
                {"content": "Mock " + task_id + " analysis evidence"},
            ])
            tasks.append({"id": task_id, "goal": "Analyze " + file, "dependsOn": [],
                          "tools": ["read_file"], "requiredTools": ["read_file"],
                          "expectedOutput": "Source evidence", "script": str(script)})
        summary = self.fixture("plan-summary", [{"content": "Mock joined analysis complete."}])
        tasks.append({"id": "summary", "goal": "Join ${controller.output} and ${service.output}",
                      "dependsOn": ["controller", "service"], "tools": [],
                      "expectedOutput": "Joined evidence", "script": str(summary)})
        definition = write_json(self.work / "plan.json", {"version": 1, "tasks": tasks})
        result = self.invoke("plan", definition, "--project", self.project,
                             "--provider", "mock", "--parallelism", 2)
        require(result["ok"] and set(result["outputs"]) == {"controller", "service", "summary"},
                "Plan did not complete both branches and join")
        require([item["taskId"] for item in result["attempts"]].count("summary") == 1,
                "Plan join did not execute exactly once")
        require(all(item["review"]["accepted"] for item in result["attempts"]), "Reviewer rejected evidence")
        for item in result["attempts"]:
            if item["taskId"] != "summary":
                require(self.trace(item["result"], "read_file")["status"] == "ok", "Worker did not read")
            else:
                goals = [message["content"] for message in item["result"]["messages"]
                         if message.get("role") == "user"]
                require(any(result["outputs"]["controller"] in goal
                            and result["outputs"]["service"] in goal for goal in goals),
                        "Join did not receive both ancestor outputs")
        return {"outputs": result["outputs"], "attempts": len(result["attempts"]),
                "joinCount": 1, "note": "Overlap is verified by the barrier unit test, not inferred from smoke timing."}

    def memory(self) -> dict[str, Any]:
        fact = "SMOKE_FACT refund example uses an in-process idempotency map."
        saved = self.invoke("memory", "save", fact, "--source", "src/demo/OrderService.java",
                            "--project", self.project)
        recalled = self.invoke("memory", "recall", "SMOKE_FACT", "--project", self.project)
        require(any(item["id"] == saved["id"] and item["content"] == fact
                    for item in recalled["memories"]), "Fact did not survive a fresh CLI process")
        return {"processes": 2, "fact": fact, "recalled": len(recalled["memories"])}

    def permissions(self) -> dict[str, Any]:
        target = self.project / "scratch.txt"
        # Exact-match patches must use identical bytes on Windows and POSIX.
        target.write_bytes(b"before\n")
        patch = self.fixture("patch", [tool_call("apply_patch", {
            "path": "scratch.txt", "before": "before\n", "after": "after\n"}),
            {"content": "Mock patch completed."}])
        denied = self.agent(patch, expected=1)
        require(self.trace(denied, "apply_patch")["status"] == "approval_required", "Missing write approval")
        require(target.read_text(encoding="utf-8") == "before\n", "Denied patch changed the file")
        approved = self.agent(patch, "--allow-write")
        require(self.trace(approved, "apply_patch")["status"] == "ok", "Approved patch failed")
        require(target.read_text(encoding="utf-8") == "after\n", "Approved patch produced wrong content")
        ambiguous = self.project / "ambiguous.txt"
        ambiguous.write_bytes(b"before\nbefore\n")
        repeated = self.fixture("ambiguous-patch", [tool_call("apply_patch", {
            "path": "ambiguous.txt", "before": "before", "after": "after"})])
        failed = self.agent(repeated, "--allow-write", expected=1)
        require(failed["status"] == "repeated_failure", "Ambiguous patch should exhaust the failure budget")
        require(ambiguous.read_text(encoding="utf-8") == "before\nbefore\n", "Ambiguous patch changed content")
        command = [str(self.java), "-version"]
        execute = self.fixture("command", [tool_call("run_command", {"command": command}),
                                           {"content": "Mock command verification complete."}])
        denied_command = self.agent(execute, expected=1)
        require(self.trace(denied_command, "run_command")["status"] == "denied", "Unlisted command ran")
        allowlist = write_json(self.work / "commands.json", [command])
        authorized = self.agent(execute, "--command-allowlist", allowlist)
        executed = self.trace(authorized, "run_command")
        result = json.loads(executed["output"])
        require(executed["status"] == "ok" and result["exitCode"] == 0 and result["output"].strip(),
                "Fixed allowlisted command did not produce real exit-zero output")
        audit = [json.loads(line) for line in (self.project / ".flowtrail" / "audit.jsonl").read_text(encoding="utf-8").splitlines()]
        writes = sum(item["tool"] == "apply_patch" and item["status"] == "ok" for item in audit)
        require(writes == 1, "Expected exactly one successful patch in the audit")
        return {"deniedWriteChangedFile": False, "successfulPatches": writes,
                "ambiguousPatchChangedFile": False, "unlistedCommandStatus": "denied",
                "approvedCommandExitCode": result["exitCode"], "commandOutput": result["output"].strip()}

    def mcp_transport(self, transport: str, config: Path, state: FixtureState) -> dict[str, Any]:
        discovered = self.invoke("mcp", config, "--project", self.project)
        require(discovered["protocolVersion"] == PROTOCOL
                and "fixture__record_note" in discovered["tools"], "MCP discovery failed")
        before = state.calls()
        arguments = json.dumps({"note": "smoke-" + transport})
        denied = self.invoke("mcp", config, "--project", self.project, "--tool", "fixture__record_note",
                             "--arguments", arguments, expected=1)
        require(denied["status"] == "approval_required" and state.calls() == before,
                "Denied MCP call produced a side effect")
        accepted = self.invoke("mcp", config, "--project", self.project, "--tool", "fixture__record_note",
                               "--arguments", arguments, "--allow-remote")
        require(accepted["status"] == "ok" and state.calls() == before + 1,
                "Approved MCP call did not produce exactly one fixture record")
        require(state.header_errors == 0, "MCP HTTP session/version/Accept headers were invalid")
        return {"transport": transport, "protocolVersion": PROTOCOL, "tools": discovered["tools"],
                "deniedSideEffects": 0, "approvedSideEffects": 1,
                "result": json.loads(accepted["output"])}

    def mcp(self) -> dict[str, Any]:
        stdio = FixtureState(self.work / "stdio-calls.jsonl")
        config = write_json(self.work / "mcp-stdio.json", {
            "name": "fixture", "transport": "stdio",
            "command": [sys.executable, str(Path(__file__).resolve()),
                        "--stdio-fixture", str(stdio.counter)],
        })
        evidence = [self.mcp_transport("stdio", config, stdio)]
        http = FixtureState(self.work / "http-calls.jsonl")
        with http_fixture(http) as url:
            config = write_json(self.work / "mcp-http.json", {"name": "fixture", "transport": "http", "url": url})
            evidence.append(self.mcp_transport("http", config, http))
        return {"mode": "local protocol fixtures", "transports": evidence}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", type=Path, default=REPOSITORY / "target" / "flowtrail.jar")
    parser.add_argument("--java", default="java", help="Java 21+ executable or PATH command")
    parser.add_argument("--output", type=Path, help="Also save the complete JSON report")
    parser.add_argument("--timeout-seconds", type=int, default=45, help="Per CLI process deadline")
    parser.add_argument("--work-dir", type=Path, help="Parent directory for the automatically cleaned temporary project")
    parser.add_argument("--stdio-fixture", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.stdio_fixture is not None:
        return stdio_fixture(args.stdio_fixture)
    jar = args.jar.resolve()
    java_path = shutil.which(args.java)
    require(java_path is not None, "Java executable was not found")
    require(jar.is_file(), "Build the distribution JAR before running this script")
    require(args.timeout_seconds > 0, "Timeout must be positive")
    java = Path(java_path).resolve()
    parent = (args.work_dir or REPOSITORY / ".cache" / "agent-smoke").resolve()
    parent.mkdir(parents=True, exist_ok=True)
    started = dt.datetime.now(dt.timezone.utc)
    with tempfile.TemporaryDirectory(prefix="run-", dir=parent) as temporary:
        smoke = Smoke(java, jar, Path(temporary), args.timeout_seconds)
        try:
            for name, action in (("legacy_run", smoke.legacy), ("index_and_search", smoke.retrieval),
                                 ("agent_and_cross_process_session", smoke.agent_and_session),
                                 ("plan_workers_and_join", smoke.plan), ("cross_process_memory", smoke.memory),
                                 ("write_and_command_permissions", smoke.permissions), ("mcp_transports", smoke.mcp)):
                smoke.check(name, action)
            report = {"schemaVersion": 1, "ok": all(check["ok"] for check in smoke.checks),
                      "mode": "local-mock", "startedAt": started.isoformat(),
                      "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                      "artifact": {"name": jar.name, "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()},
                      "cliInvocations": smoke.invocations, "checks": smoke.checks}
            encoded = json.dumps(report, ensure_ascii=False, indent=2)
            for source, replacement in ((temporary, "[temporary]"), (str(REPOSITORY), "[repository]"),
                                        (str(java), "[java]"), (sys.executable, "[python]")):
                encoded = encoded.replace(json.dumps(source, ensure_ascii=False)[1:-1], replacement)
        finally:
            smoke.cleanup()
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as error:
        print(json.dumps({"ok": False, "mode": "local-mock", "error": str(error)}, ensure_ascii=False))
        raise SystemExit(1)
