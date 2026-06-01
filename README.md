# EDT Debug Tracer — MCP Server for Eclipse EDT

Eclipse/EDT plugin that hooks into the debug API, traces BSL execution step-by-step,
stores events in SQLite and exposes a local HTTP MCP API for Vanessa and AI agents.

## Quick Start

```
git clone https://github.com/lytkinam/edt-debug-tracer
cd edt-debug-tracer
# Follow docs/06_dev_environment.md
```

## Architecture

```
Vanessa / AI Agent
  │  POST /mcp/start
  ▼
McpHttpServer (localhost:18080)
  │
  ▼
DebugTracerListener  ←── Eclipse Debug Events (SUSPEND)
  │
  ▼
AsyncTraceWriter
  │
  ▼
TraceRepository (SQLite: raw_trace)
  │  POST /mcp/postprocess
  ▼
LoopCollapser → clean_trace
  │  GET /mcp/trace
  ▼
JSON response to agent
```

## Docs

- [01 Context](docs/01_context.md)
- [02 Architecture](docs/02_architecture.md)
- [03 Eclipse Debug API](docs/03_eclipse_debug_api.md)
- [04 MCP Server API](docs/04_mcp_server.md)
- [08 Trace Storage (SQLite)](docs/08_trace_storage.md)
- [09 PDE Test Project](docs/09_pde_test_project.md)
- [10 Lib Checklist (sqlite-jdbc)](docs/10_lib_checklist.md)

## Tests

- Python smoke tests: `tests/test_mcp_api.py`
- Java unit + integration tests: `plugin.tests/`
