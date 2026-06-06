# SRS — HTTP-метод `wait_for_break`

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Статус:** ✅ Метод существует  
**Связанный BRD:** [BRD-wait_for_break.md](BRD-wait_for_break.md)

---

## 1. Транспорт

```
POST /mcp
Content-Type: application/json
```

## 2. Запрос

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "wait_for_break",
    "arguments": {
      "projectName": "smallbase",
      "timeoutMs": 60000
    }
  }
}
```

### 2.1 Аргументы

| Поле | Тип | Обязательное | Описание |
|---|---|:---:|---|
| `projectName` | string | ✓ | Имя проекта |
| `timeoutMs` | integer > 0 | ✓ | Максимальное время ожидания события SUSPEND в мс |

## 3. Ответ — точка достигнута

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"projectName\":\"smallbase\",\"suspended\":true,\"threadId\":\"RuntimeDebugTargetThread@4bbe344a\",\"reason\":\"suspended\",\"elapsedMs\":102,\"timeout\":false}"
    }]
  }
}
```

## 4. Ответ — таймаут

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"projectName\":\"smallbase\",\"suspended\":false,\"threadId\":null,\"reason\":\"timeout\",\"elapsedMs\":60000,\"timeout\":true}"
    }]
  }
}
```

### 4.1 Поля `text` (JSON)

| Поле | Тип | Описание |
|---|---|---|
| `suspended` | bool | `true` — событие получено, поток приостановлен |
| `threadId` | string\|null | ID потока (обязателен при `suspended: true`) |
| `timeout` | bool | `true` — истёк `timeoutMs` без события |
| `elapsedMs` | integer | Фактическое время ожидания |

## 5. Поведение оркестратора при получении ответа

| Ответ сервера | Действие оркестратора |
|---|---|
| `suspended: true` | Сохранить `threadId`, перейти к шагу 4 (`get_call_stack`) |
| `timeout: true` | `exit 3` (RUNTIME_ERROR: точка не достигнута) |
| `error` от JSON-RPC | `exit 3` |
| Нет соединения | `exit 1` (NETWORK_ERROR) |
