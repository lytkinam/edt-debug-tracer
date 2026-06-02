# Update Summary

## v1.0 — Pipeline Tracer (текущая версия)

### Что работает

- **TracerListener** (`IDebugEventSetListener`) — перехват SUSPEND-событий Eclipse Debug API
- **Pipeline auto-step** — `thread.stepOver()` напрямую из event handler, запись через `BlockingQueue` в writer-thread
- **HttpServer** (com.sun.net.httpserver) — REST API на настраиваемом порту
- **Per-workspace конфигурация** — `{workspace}/.edt-debug-tracer/tracer.properties`
- **Запись в файл** — JSON-массив `StepEntry[]` при `/mcp/stop`
- **PDE JUnit тесты** — через AssistAI MCP `eclipse-pde` endpoint

### Производительность

- Pipeline: **192 steps/sec** на Java test app, **7 steps/sec** на реальном BSL (EDT)
- 0% потерь шагов
- Hot path: ~12μs (frame read + queue + stepOver)

### API Endpoints

| Endpoint | Метод | Описание |
|----------|-------|----------|
| `/mcp/health` | GET | Статус: ok, recording, autoStepping, entries, port |
| `/mcp/start` | POST | Начать запись |
| `/mcp/run` | POST | Auto-step: `{"steps": N}` |
| `/mcp/stop` | POST | Остановить, сохранить в файл |

### Файлы

```
plugin/src/plugin17/test/
├── TestActivator.java       — BundleActivator + HttpServer + config reader
├── TracerListener.java      — IDebugEventSetListener + pipeline auto-step
├── StepEntry.java           — record(procedure, line, module, threadId, ts)
├── McpHealthTest.java       — PDE JUnit: health endpoint
└── TracerIntegrationTest.java — PDE JUnit: start/stop recording
```

### Конфигурация

`{workspace}/.edt-debug-tracer/tracer.properties`:
```properties
port=18080
output=~/.edt-debug-tracer/trace.json
```

### Установка

1. Собрать jar (javac + jar)
2. Скопировать в `$EDT_DIR/plugins/`
3. Добавить в `bundles.info`
4. Перезапустить EDT (без `-clean`)

### Протестировано

- ✅ Eclipse 2026-03 (port 18060, workspace-eclipse-latest)
- ✅ EDT 2025.1.5 (port 18080, workspace-edt2025)
- ✅ 30 шагов реального BSL-кода без потерь
- ✅ PDE JUnit тесты (2/2 passed)

---

## Предыдущие итерации (в git history)

### v0.3 — MCP Debug Control Server (не вошло в v1.0)

- DebugService с 8 JSON-RPC tools (debug_status, step, resume, suspend, wait_for_break, get_variables, get_call_stack, list_sessions)
- 20 DTO records
- McpJsonRpcHandler
- Отложено: требует полной интеграции с Eclipse PDE build

### v0.2 — Event-driven auto-step
- stepOver через `new Thread()` — 48 steps/sec
- Pipeline (direct stepOver) — 192 steps/sec
- Выявлен threading: stepOver не работает из event dispatch thread

### v0.1 — Minimal plugin
- BundleActivator + HttpServer + /mcp/health, /mcp/start, /mcp/stop
- In-memory `CopyOnWriteArrayList`
- Доказательство загрузки OSGi бандла в Eclipse 2026-03 и EDT
