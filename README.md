# EDT Debug Tracer — MCP Server for Eclipse EDT

Eclipse/EDT plugin that hooks into the debug API, traces BSL execution step-by-step,
stores events in SQLite and exposes a local HTTP MCP API for Vanessa and AI agents.

**Two modes:**
- **Trace Recording** — passive step logging to SQLite with post-processing (REST API)
- **Debug Control** — live debug interaction: step, resume, suspend, variables, call stack (JSON-RPC 2.0 MCP)

## Installation

### Via p2 Update Site (recommended)

1. In EDT: `Help → Install New Software...`
2. `Add...` → Location: `https://lytkinam.github.io/edt-debug-tracer/`
3. Select **EDT Debug Tracer** → Next → Finish → Restart

### Via Build Script

```bash
git clone https://github.com/lytkinam/edt-debug-tracer
cd edt-debug-tracer
./scripts/build.sh --install    # compile + copy to EDT dropins
# Restart EDT (without -clean)
```

## Architecture

```
Vanessa / AI Agent
  │
  │  JSON-RPC 2.0 (debug control)    REST (trace recording)
  │  POST /mcp                         POST /mcp/start, /mcp/stop, ...
  ▼                                    ▼
┌──────────────────────────────────────────────────────┐
│  McpHttpServer (localhost:18080)                      │
│    ├── McpJsonRpcHandler  ── 8 debug control tools   │
│    └── REST handlers  ── 6 trace recording endpoints │
├──────────────────────────────────────────────────────┤
│  DebugService          DebugTracerListener            │
│    step/resume/suspend    listens SUSPEND events      │
│    getVariables           reads IStackFrame           │
│    getCallStack           → AsyncTraceWriter          │
│    waitForBreak           → TraceRepository (SQLite)  │
│    listSessions           → LoopCollapser             │
│  WaitForBreakListener                                 │
│    event-driven via CompletableFuture                 │
└──────────────────────────────────────────────────────┘
         ▲              ▲
    Vanessa      AI Agent (MCP client)
```

## MCP Debug Control Tools (JSON-RPC 2.0)

Endpoint: `POST http://localhost:18080/mcp`

| Tool | Description |
|------|-------------|
| `debug_status` | Current debug state (suspended/running/inactive) |
| `step` | Step into/over/out |
| `resume` | Resume suspended thread |
| `suspend` | Suspend running thread |
| `wait_for_break` | Event-driven wait for SUSPEND |
| `get_variables` | Stack frame variables + position (line, frameName, sourcePath) |
| `get_call_stack` | Full call stack for all threads |
| `list_sessions` | Active debug sessions with IDs |

Example:
```bash
curl -X POST http://localhost:18080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"debug_status","arguments":{"projectName":"my_project"}}}'
```

## Trace Recording (REST)

| Endpoint | Description |
|----------|-------------|
| `GET /mcp/health` | Liveness check |
| `GET /mcp/status` | Current trace session state |
| `POST /mcp/start` | Begin recording `{session_id}` |
| `POST /mcp/stop` | Stop recording, get step count |
| `POST /mcp/postprocess` | Collapse repeats via LoopCollapser |
| `GET /mcp/trace?session=&type=raw\|clean` | Get trace as JSON |

## Docs

- [01 Context](docs/01_context.md)
- [02 Architecture](docs/02_architecture.md)
- [03 Eclipse Debug API](docs/03_eclipse_debug_api.md)
- [04 MCP Server API](docs/04_mcp_server.md)
- [08 Trace Storage (SQLite)](docs/08_trace_storage.md)
- [09 PDE Test Project](docs/09_pde_test_project.md)
- [10 Lib Checklist (sqlite-jdbc)](docs/10_lib_checklist.md)

## Build

```bash
./scripts/build.sh              # compile + package jar
./scripts/build.sh --install    # + copy to EDT dropins
./scripts/build.sh --clean      # remove build artifacts
```

Requires: EDT installed at `/opt/1C/1CE/components/1c-edt-*/`

## Tests

- Python smoke tests: `tests/test_mcp_api.py`
- Java unit + integration tests: `plugin.tests/`
