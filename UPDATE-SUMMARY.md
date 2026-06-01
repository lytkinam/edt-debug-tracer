# Update Summary

## v3 — MCP Debug Control Server

### What changed

- Added `DebugService` — direct Eclipse Debug API access for live debug control
- Added `WaitForBreakListener` — event-driven SUSPEND via `IDebugEventSetListener` + `CompletableFuture`
- Added `McpJsonRpcHandler` — JSON-RPC 2.0 dispatcher with 8 MCP tools
- Added 20 DTO records in `com.tracer.edt.mcp.dto`
- Modified `McpHttpServer` — added `/mcp` JSON-RPC endpoint alongside REST
- Modified `DebugTracerActivator` — wires DebugService + WaitForBreakListener + McpJsonRpcHandler
- Added `scripts/build.sh` — CLI build without Eclipse PDE (javac + jar)
- Added `site/` — p2 update site for EDT installation
- Updated `MANIFEST.MF` — export `com.tracer.edt.debug`, `com.tracer.edt.mcp.dto`
- Updated README, docs/02, docs/04

### New MCP tools (JSON-RPC 2.0 on `POST /mcp`)

| Tool | Description |
|------|-------------|
| `debug_status` | Current debug state |
| `step` | Step into/over/out |
| `resume` | Resume suspended thread |
| `suspend` | Suspend running thread |
| `wait_for_break` | Event-driven wait for SUSPEND |
| `get_variables` | Stack frame variables + position |
| `get_call_stack` | Full call stack for all threads |
| `list_sessions` | Active debug sessions |

### Installation

p2 update site: `https://lytkinam.github.io/edt-debug-tracer/`

---

## v2 — Async SQLite Pipeline

- Replaced in-memory `StepLogBuffer` with async SQLite pipeline
- Added `AsyncTraceWriter` (queue + background thread)
- Added `TraceRepository` (raw_trace + clean_trace tables)
- Added `TraceSessionManager`
- Added `LoopCollapser` (repeat collapse + pattern detection)
- Added `CollapsedTraceEntry` with JSON serialization
- Extended `McpHttpServer` with `/mcp/postprocess` and `/mcp/trace`
- Added `plugin.tests/` PDE test project with 3 Java test classes
- Added `docs/09_pde_test_project.md`
- Added `docs/10_lib_checklist.md`

### sqlite-jdbc dependency

Download: https://github.com/xerial/sqlite-jdbc/releases/download/3.53.1.0/sqlite-jdbc-3.53.1.0.jar
Place as: plugin/lib/sqlite-jdbc.jar AND plugin.tests/lib/sqlite-jdbc.jar
