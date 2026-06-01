# 04. MCP Server API

Base URL: `http://localhost:18080`

Two protocols on the same server:
- **REST** — trace recording (6 endpoints)
- **JSON-RPC 2.0** — debug control (8 tools, endpoint: `POST /mcp`)

---

## JSON-RPC 2.0 — Debug Control

Endpoint: `POST /mcp`  
Content-Type: `application/json`

### tools/list

Returns all available tool definitions.

Request:
```json
{"jsonrpc":"2.0","id":1,"method":"tools/list"}
```

### tools/call

Dispatches to a debug tool by name.

Request:
```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"<tool_name>","arguments":{...}}}
```

Response:
```json
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"<json_result>"}]}}
```

### Tool: debug_status

Current debug state overview.

Arguments: `{"projectName": "string"}` (optional)

Result:
```json
{"projectName":"my_project","state":"suspended","suspended":true,
 "launchCount":1,"targetCount":1,"breakpointCount":0,
 "activeThreadId":"RuntimeDebugTargetThread@4bbe344a","message":"Debug status collected"}
```

### Tool: step

Step into/over/out in the debugger.

Arguments: `{"projectName": "string", "threadId": "string", "kind": "into|over|out"}`

Result:
```json
{"projectName":"my_project","threadId":"...","kind":"into","status":"started","message":"Step command sent"}
```

### Tool: resume

Resume a suspended debug thread.

Arguments: `{"projectName": "string", "threadId": "string"}`

Result:
```json
{"projectName":"my_project","threadId":"...","status":"resumed","message":"Resume command sent"}
```

### Tool: suspend

Suspend a running debug thread.

Arguments: `{"projectName": "string", "threadId": "string"}`

Result:
```json
{"projectName":"my_project","threadId":"...","status":"suspended","message":"Suspend command sent"}
```

### Tool: wait_for_break

Event-driven wait for SUSPEND event (no polling).

Arguments: `{"projectName": "string", "timeoutMs": 30000}`

Result:
```json
{"projectName":"my_project","suspended":true,"threadId":"...",
 "reason":"suspended","elapsedMs":102,"timeout":false}
```

### Tool: get_variables

Get stack frame variables and position info.

Arguments: `{"projectName": "string", "threadId": "string", "frameId": "string"}`

Result:
```json
{"projectName":"my_project","threadId":"...","frameId":"...",
 "lineNumber":30,"frameName":"ОбщийМодуль.Метод(...) строка: 30",
 "sourcePath":null,"charStart":-1,"charEnd":-1,
 "variables":[{"name":"x","type":"Число","value":"42","hasChildren":false}]}
```

### Tool: get_call_stack

Full call stack for all debug threads.

Arguments: `{"projectName": "string", "threadId": "string"}`

Result:
```json
{"projectName":"my_project","activeThreadId":"...",
 "threads":[{
   "threadId":"...","threadName":"Сервер [admin]","threadState":"suspended",
   "frames":[
     {"frameId":"...","frameName":"...","lineNumber":30,"charStart":-1,"charEnd":-1,"variableCount":7},
     {"frameId":"...","frameName":"...","lineNumber":16,"charStart":-1,"charEnd":-1,"variableCount":1}
   ]
 }]}
```

### Tool: list_sessions

List active debug sessions.

Arguments: `{"projectName": "string"}` (optional filter)

Result:
```json
{"sessions":[{
  "sessionId":"session-1a2b3c4d","projectName":"my_project",
  "state":"suspended","targetCount":1,"activeThreadId":"..."
}]}
```

---

## REST — Trace Recording

### GET /mcp/health
Returns server liveness.
```json
{"ok": true, "version": "1.0.0"}
```

### GET /mcp/status
Returns current session state.
```json
{"active": true, "session_id": "van-001", "steps": 142}
```

### POST /mcp/start
Request:
```json
{"session_id": "van-001"}
```
Response 200:
```json
{"started": true, "session_id": "van-001"}
```

### POST /mcp/stop
Response 200:
```json
{"stopped": true, "session_id": "van-001", "steps": 142}
```

### POST /mcp/postprocess
Request:
```json
{"session_id": "van-001"}
```
Response 200:
```json
{"ok": true, "raw": 142, "clean": 38}
```

### GET /mcp/trace?session=van-001&type=raw
Returns JSON array of trace entries.
`type` = `raw` | `clean` (default: `clean`)

## Error format
```json
{"error": "description", "code": 400}
```
