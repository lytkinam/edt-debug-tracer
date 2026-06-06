# SRS — HTTP-метод `set_breakpoint`

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Статус:** ⛔ ТРЕБОВАНИЕ (метод отсутствует — необходима реализация)  
**Связанный BRD:** [BRD-set_breakpoint.md](BRD-set_breakpoint.md)

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
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "set_breakpoint",
    "arguments": {
      "projectName": "smallbase",
      "file": "CommonModules/ПроверкаРезультата/Module.bsl",
      "line": 42
    }
  }
}
```

### 2.1 Аргументы

| Поле | Тип | Обязательное | Описание |
|---|---|:---:|---|
| `projectName` | string | ✓ | Имя проекта в EDT workspace |
| `file` | string | ✓ | Путь к файлу относительно корня проекта |
| `line` | integer > 0 | ✓ | Номер строки точки останова |

## 3. Ответ — успех

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"set\":true,\"breakpointId\":\"bp_42\",\"file\":\"CommonModules/ПроверкаРезультата/Module.bsl\",\"line\":42,\"message\":\"Breakpoint set\"}"
    }]
  }
}
```

### 3.1 Поля `text` (JSON)

| Поле | Тип | Описание |
|---|---|---|
| `set` | bool | `true` — точка установлена |
| `breakpointId` | string | Идентификатор точки (для будущей очистки) |
| `file` | string | Подтверждение файла |
| `line` | integer | Подтверждение строки |
| `message` | string | Человекочитаемое сообщение |

## 4. Ответ — ошибка

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32000,
    "message": "File not found: CommonModules/Неизвестный/Module.bsl"
  }
}
```

## 5. Поведение при ошибках

| Ситуация | Ожидаемый ответ |
|---|---|
| Файл не найден в проекте | `error.message` с именем файла |
| Строка за пределами файла | `error.message` с номером строки |
| Проект не найден | `error.message` с именем проекта |
| Повторная установка на ту же строку | `set: true` (идемпотентно, без ошибки) |

## 6. Поведение оркестратора при получении ответа

| Ответ сервера | Действие оркестратора |
|---|---|
| `set: true` | Переход к шагу 2 (`resume`) |
| `error` от JSON-RPC | `exit 3` (RUNTIME_ERROR) |
| Нет соединения | `exit 1` (NETWORK_ERROR) |
