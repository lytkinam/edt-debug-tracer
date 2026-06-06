# SRS — HTTP-метод `get_variables`

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Статус:** ✅ Метод существует  
**Связанный BRD:** [BRD-get_variables.md](BRD-get_variables.md)

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
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "get_variables",
    "arguments": {
      "projectName": "smallbase",
      "threadId": "RuntimeDebugTargetThread@4bbe344a",
      "frameId": "StackFrame@1a2b3c"
    }
  }
}
```

### 2.1 Аргументы

| Поле | Тип | Обязательное | Описание |
|---|---|:---:|---|
| `projectName` | string | ✓ | Имя проекта |
| `threadId` | string | ✓ | ID потока из `wait_for_break` |
| `frameId` | string | ✓ | ID фрейма из `get_call_stack.frames[0]` |

## 3. Ответ — успех

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"projectName\":\"smallbase\",\"threadId\":\"...\",\"frameId\":\"...\",\"lineNumber\":42,\"frameName\":\"...\",\"variables\":[{\"name\":\"Результат\",\"type\":\"Булево\",\"value\":\"Истина\",\"hasChildren\":false}]}"
    }]
  }
}
```

### 3.1 Поля `text` (JSON) — нужные оркестратору

| Поле | Тип | Описание |
|---|---|---|
| `variables` | array | Список переменных фрейма |
| `variables[].name` | string | Имя переменной |
| `variables[].type` | string | Тип (Число, Строка, Булево, ...) |
| `variables[].value` | string | Строковое представление значения |

## 4. Поведение оркестратора при получении ответа

| Ответ сервера | Действие оркестратора |
|---|---|
| `variables` получены | Отфильтровать по именам из запроса, перейти к шагу 6 |
| Пустой `variables` | Продолжить (вернуть пустой объект в ответе) |
| `error` от JSON-RPC | `exit 3` |
| Нет соединения | `exit 1` |
