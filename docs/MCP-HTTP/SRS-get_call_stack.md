# SRS — HTTP-метод `get_call_stack`

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Статус:** ✅ Метод существует  
**Связанный BRD:** [BRD-get_call_stack.md](BRD-get_call_stack.md)

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
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "get_call_stack",
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
  "id": 4,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"projectName\":\"smallbase\",\"activeThreadId\":\"...\",\"threads\":[{\"threadId\":\"...\",\"threadName\":\"Сервер [admin]\",\"threadState\":\"suspended\",\"frames\":[{\"frameId\":\"...\",\"frameName\":\"ОбщийМодуль.ПроверкаРезультата.Метод(...) строка: 42\",\"lineNumber\":42,\"variableCount\":7}]}]}"
    }]
  }
}
```

### 3.1 Поля `text` (JSON) — нужные оркестратору

| Поле | Тип | Описание |
|---|---|---|
| `threads[].frames[0].frameId` | string | ID верхнего фрейма → передаётся в `get_variables` |
| `threads[].frames[0].frameName` | string | Строка стека → в `callStack` итогового ответа |
| `threads[].frames[0].lineNumber` | integer | Проверка: должна совпасть с запрошенной строкой |

## 4. Поведение оркестратора при получении ответа

| Ответ сервера | Действие оркестратора |
|---|---|
| Фреймы получены | Взять `frameId` из `frames[0]`, перейти к шагу 5 |
| Пустой список `frames` | `exit 3` (RUNTIME_ERROR) |
| `error` от JSON-RPC | `exit 3` |
| Нет соединения | `exit 1` |
