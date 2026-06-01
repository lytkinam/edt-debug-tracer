# 02. Architecture

## Components

```
┌──────────────────────────────────────────────────────────────────┐
│  Eclipse IDE (EDT installed as plugin)                           │
│                                                                  │
│  DebugTracerActivator  (OSGi Bundle start/stop)                  │
│    │                                                             │
│    ├── [Trace Recording]                                         │
│    │   ├── DebugTracerListener                                   │
│    │   │     listens: IDebugEventSetListener                     │
│    │   │     on SUSPEND → reads IStackFrame → enqueues           │
│    │   │                                                         │
│    │   ├── AsyncTraceWriter                                      │
│    │   │     background thread, queue(10K), batch(50)            │
│    │   │     → TraceRepository.insertRawBatch()                  │
│    │   │                                                         │
│    │   ├── TraceRepository  (SQLite via JDBC)                    │
│    │   │     tables: sessions, raw_trace, clean_trace            │
│    │   │                                                         │
│    │   └── LoopCollapser                                         │
│    │         STEP / REPEAT / LOOP pattern detection              │
│    │                                                             │
│    ├── [Debug Control]                                           │
│    │   ├── DebugService                                          │
│    │   │     step, resume, suspend, getVariables,                │
│    │   │     getCallStack, listSessions, debugStatus,            │
│    │   │     waitForBreak — direct Eclipse Debug API             │
│    │   │                                                         │
│    │   └── WaitForBreakListener                                  │
│    │         IDebugEventSetListener + CompletableFuture<IThread> │
│    │         event-driven SUSPEND notification                   │
│    │                                                             │
│    └── McpHttpServer  (localhost:18080)                           │
│          ├── REST handlers (trace recording)                     │
│          │     POST /mcp/start, /mcp/stop, /mcp/postprocess      │
│          │     GET  /mcp/health, /mcp/status, /mcp/trace         │
│          │                                                       │
│          └── McpJsonRpcHandler (debug control)                   │
│                POST /mcp  (JSON-RPC 2.0)                         │
│                8 tools: debug_status, step, resume, suspend,     │
│                wait_for_break, get_variables, get_call_stack,    │
│                list_sessions                                     │
└──────────────────────────────────────────────────────────────────┘
         ▲              ▲
    Vanessa      AI Agent (MCP client)
```

## Data flows

### Trace Recording (REST)

1. Vanessa calls `POST /mcp/start {session_id}`
2. 1C application runs under EDT debugger
3. Each step → SUSPEND event → TraceEntry → SQLite raw_trace
4. Vanessa calls `POST /mcp/stop`
5. Agent calls `POST /mcp/postprocess` → LoopCollapser → clean_trace
6. Agent calls `GET /mcp/trace?session=...&type=clean` → JSON

### Debug Control (JSON-RPC 2.0)

1. Agent sends `POST /mcp` with `{"method":"tools/call","params":{"name":"step","arguments":{...}}}`
2. McpJsonRpcHandler dispatches to DebugService
3. DebugService calls Eclipse Debug API directly (no reflection)
4. For `wait_for_break`: WaitForBreakListener catches SUSPEND event → CompletableFuture completes
5. Result serialized as JSON-RPC response

## Key design decisions

- **Direct API access** — no reflection needed (unlike codepilot1c-edt patches)
- **Separate listeners** — DebugTracerListener (trace) and WaitForBreakListener (debug control) are independent
- **Non-blocking writes** — AsyncTraceWriter never blocks the Eclipse debug event thread
- **SQLite persistence** — traces survive EDT restarts, queryable via SQL
- **JSON-RPC 2.0** — same MCP protocol as codepilot1c-edt, compatible with Python MCP clients
