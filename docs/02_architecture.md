# 02. Architecture

## Components

```
┌─────────────────────────────────────────────────────┐
│  Eclipse IDE (EDT installed as plugin)               │
│                                                     │
│  DebugTracerActivator  (OSGi Bundle start/stop)     │
│    │                                                │
│    ├── DebugTracerListener                          │
│    │     listens: IDebugEventSetListener            │
│    │     on SUSPEND → reads IStackFrame             │
│    │     → enqueues TraceEntry                      │
│    │                                                │
│    ├── AsyncTraceWriter                             │
│    │     background thread drains queue             │
│    │     → TraceRepository.insertRawBatch()         │
│    │                                                │
│    ├── TraceRepository  (SQLite via JDBC)           │
│    │     tables: sessions, raw_trace, clean_trace   │
│    │                                                │
│    └── McpHttpServer  (localhost:18080)             │
│          POST /mcp/start                            │
│          POST /mcp/stop                             │
│          POST /mcp/postprocess                      │
│          GET  /mcp/trace?session=&type=             │
│          GET  /mcp/status                           │
│          GET  /mcp/health                           │
└─────────────────────────────────────────────────────┘
         ▲              ▲
    Vanessa      AI Agent (MCP client)
```

## Data flow

1. Vanessa calls `POST /mcp/start {session_id}`
2. 1C application runs under EDT debugger
3. Each step → SUSPEND event → TraceEntry → SQLite raw_trace
4. Vanessa calls `POST /mcp/stop`
5. Agent calls `POST /mcp/postprocess` → LoopCollapser → clean_trace
6. Agent calls `GET /mcp/trace?session=...&type=clean` → JSON
