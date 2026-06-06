# SRS — HTTP-метод `suspend`

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Статус:** ✅ Метод существует  
**Связанный BRD:** [BRD-suspend.md](BRD-suspend.md)

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
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "suspend",
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
| `threadId` | string | ✓ | ID потока из `wait_for_break` |

## 3. Ответ — успех

```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"projectName\":\"smallbase\",\"threadId\":\"...\",\"status\":\"suspended\",\"message\":\"Suspend command sent\"}"
    }]
  }
}
```

### 3.1 Поля `text` (JSON)

| Поле | Тип | Описание |
|---|---|---|
| `status` | string | `"suspended"` |
| `message` | string | Подтверждение |

## 4. Поведение оркестратора при получении ответа

| Ответ сервера | Действие оркестратора |
|---|---|
| `status: "suspended"` | Сформировать итоговый JSON, `exit 0` |
| `error` от JSON-RPC | Логировать, но всё равно `exit 0` — данные уже собраны |
| Нет соединения | Логировать, но всё равно `exit 0` — данные уже собраны |
