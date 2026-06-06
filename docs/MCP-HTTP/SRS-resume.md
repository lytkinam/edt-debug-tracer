# SRS — HTTP-метод `resume`

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Статус:** ✅ Метод существует  
**Связанный BRD:** [BRD-resume.md](BRD-resume.md)

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
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "resume",
    "arguments": {
      "projectName": "smallbase",
      "threadId": "RuntimeDebugTargetThread@4bbe344a"
    }
  }
}
```

### 2.1 Аргументы

| Поле | Тип | Обязательное | Описание |
|---|---|:---:|---|
| `projectName` | string | ✓ | Имя проекта |
| `threadId` | string | ✓ | ID потока из `debug_status` или `wait_for_break` |

## 3. Ответ — успех

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"projectName\":\"smallbase\",\"threadId\":\"...\",\"status\":\"resumed\",\"message\":\"Resume command sent\"}"
    }]
  }
}
```

### 3.1 Поля `text` (JSON)

| Поле | Тип | Описание |
|---|---|---|
| `status` | string | `"resumed"` |
| `message` | string | Подтверждение команды |

## 4. Поведение оркестратора при получении ответа

| Ответ сервера | Действие оркестратора |
|---|---|
| `status: "resumed"` | Переход к шагу 3 (`wait_for_break`) |
| `error` от JSON-RPC | `exit 3` (RUNTIME_ERROR) |
| Нет соединения | `exit 1` (NETWORK_ERROR) |
