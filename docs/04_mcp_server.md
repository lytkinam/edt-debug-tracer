# 04. MCP Server API

Base URL: `http://localhost:18080`

## Endpoints

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
or
```json
{"active": false}
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
Response 409 (already active):
```json
{"error": "session already active", "session_id": "van-001"}
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
