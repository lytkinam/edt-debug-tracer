# 04. MCP HTTP Server API

## Базовый URL

```
http://localhost:18080/mcp
```

Порт можно переопределить системным свойством при запуске Eclipse:
```
-Dedt.tracer.port=18080
```

## Эндпоинты

### GET /mcp/health

Проверка, что сервер жив.

**Ответ 200:**
```json
{ "status": "ok", "version": "1.0.0" }
```

---

### GET /mcp/status

Текущее состояние трассировки.

**Ответ 200:**
```json
{
  "tracing": false,
  "entries_count": 0,
  "session_id": null
}
```

---

### POST /mcp/start

Начать запись трейса. Очищает предыдущий буфер.

**Тело (опционально):**
```json
{ "session_id": "vanessa-step-42" }
```

**Ответ 200:**
```json
{ "started": true, "session_id": "vanessa-step-42" }
```

**Ошибка (трассировка уже идёт), 409:**
```json
{ "error": "already_running", "message": "Tracing session is already active" }
```

---

### POST /mcp/stop

Остановить запись и вернуть трейс.

**Ответ 200:**
```json
{
  "stopped": true,
  "session_id": "vanessa-step-42",
  "entries": [
    {
      "module": "ОбщийМодуль.УправлениеДолгами",
      "line": 42,
      "procedure": "РассчитатьДолг",
      "timestamp": "2026-05-31T20:00:01.001Z",
      "thread": "main"
    }
  ]
}
```

**Ошибка (трассировка не запущена), 409:**
```json
{ "error": "not_running", "message": "No active tracing session" }
```

---

## Коды ошибок

| HTTP | error | Причина |
|------|-------|---------|
| 409 | already_running | start вызван дважды |
| 409 | not_running | stop без start |
| 500 | internal_error | необработанное исключение |
