# 02. Архитектура

## Компоненты

```
┌─────────────────────────────────────────────────────┐
│  Eclipse / 1C:EDT                                   │
│                                                     │
│  ┌──────────────────────┐                           │
│  │  DebugTracerActivator │ OSGi bundle start/stop   │
│  └──────────┬───────────┘                           │
│             │                                       │
│  ┌──────────▼───────────┐   ┌───────────────────┐  │
│  │  DebugTracerListener  │──▶│  StepLogBuffer    │  │
│  │  IDebugEventSetLstnr  │   │  List<TraceEntry> │  │
│  └──────────────────────┘   └───────────────────┘  │
│                                       │             │
│  ┌────────────────────────────────────▼──────────┐  │
│  │  McpHttpServer  (localhost:18080)              │  │
│  │  GET /mcp/health  GET /mcp/status              │  │
│  │  POST /mcp/start  POST /mcp/stop               │  │
│  └───────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────┘
                             │ HTTP
          ┌──────────────────┴──────────────────┐
          │                                     │
   ┌──────▼──────┐                    ┌─────────▼──────┐
   │   Vanessa   │                    │   AI-агент     │
   │  (BDD тест) │                    │ (MCP client)   │
   └─────────────┘                    └────────────────┘
```

## Поток данных

1. **OSGi старт** → `DebugTracerActivator.start()` регистрирует `DebugTracerListener` в `DebugPlugin` и запускает `McpHttpServer`.
2. **Vanessa / агент** → `POST /mcp/start` → `StepLogBuffer.startSession()`.
3. **Событие отладчика** → `DebugTracerListener.handleDebugEvents()` → при `SUSPEND` читает `IThread.getTopStackFrame()`, создаёт `TraceEntry`, кладёт в буфер.
4. **Vanessa / агент** → `POST /mcp/stop` → `StepLogBuffer.stopSession()` → возвращает JSON-массив `TraceEntry`.
5. **OSGi стоп** → `DebugTracerActivator.stop()` останавливает HTTP-сервер, убирает listener.

## TraceEntry (JSON)

```json
{
  "module": "ОбщийМодуль.УправлениеДолгами",
  "line": 42,
  "procedure": "РассчитатьДолг",
  "timestamp": "2026-05-31T20:00:00.123Z",
  "thread": "main"
}
```
