# SRS — HTTP-метод: `poll_break_status`

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Статус:** Draft — ожидает реализации HTTP-стоком

---

## Потребители контракта

| Компонент | Роль | Как использует |
|---|---|---|
| `tracer-sink` CLI | Оркестратор | Шаг 3: вызывает каждые `pollIntervalMs` мс до `suspended: true` или таймаута |

---

## Эндпоинт

```
POST http://{host}:{port}/mcp
Content-Type: application/json
```

## Запрос

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "poll_break_status",
    "arguments": {
      "project": "my-project",
      "session_id": "sess-abc123"
    }
  }
}
```

| Поле | Тип | Обязательное | Описание |
|---|---|:---:|---|
| `project` | `string` | ✅ | Имя проекта EDT |
| `session_id` | `string` | ✅ | Идентификатор сессии, полученный от `set_breakpoint` |

---

## Ответ — поток выполняется

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"suspended\":false,\"status\":\"running\"}"
    }]
  }
}
```

## Ответ — поток остановлен

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"suspended\":true,\"threadId\":\"thread-1\",\"file\":\"Module.bsl\",\"line\":42,\"session_id\":\"sess-abc123\"}"
    }]
  }
}
```

| Поле | Тип | Условие | Описание |
|---|---|---|---|
| `suspended` | `boolean` | всегда | `true` — поток остановлен на точке |
| `status` | `string` | если `false` | Текущий статус: `running`, `stepping` |
| `threadId` | `string` | если `true` | ID остановленного потока |
| `file` | `string` | если `true` | Файл, на котором остановлен поток |
| `line` | `integer` | если `true` | Строка останова |
| `session_id` | `string` | если `true` | Эхо идентификатора сессии |

---

## Поведение при ошибках

| Условие | HTTP-ответ | Действие оркестратора |
|---|---|---|
| HTTP-сервер недоступен | Нет соединения | Повторить через `pollIntervalMs`; после N попыток → `exit 1` |
| `session_id` не найден | JSON-RPC error | `exit 3` |

---

## Логика оркестратора (polling loop)

```
deadline = now() + timeoutMs
while now() < deadline:
    response = POST /mcp {poll_break_status, session_id}
    if network_error:
        sleep(pollIntervalMs)
        continue          # повтор — идемпотентно
    if response.suspended == true:
        threadId = response.threadId
        break             # переход к шагу 4
    sleep(pollIntervalMs)

if now() >= deadline:
    exit 3 «таймаут ожидания останова»
```

**Рекомендуемые параметры оркестратора:**
- `pollIntervalMs`: 250 мс
- Максимум повторов при сетевой ошибке: 3
