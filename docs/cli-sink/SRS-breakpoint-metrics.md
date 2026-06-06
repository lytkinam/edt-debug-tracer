# SRS — CLI-Сток: метод `breakpoint-metrics`

**Версия:** 1.1  
**Дата:** 2026-06-06  
**Репозиторий:** lytkinam/edt-debug-tracer, ветка `feature/trace-sink`  
**Связанный документ:** docs/cli-sink/BRD-breakpoint-metrics.md  
**Статус:** Draft

---

## 1. Обзор

Метод `breakpoint-metrics` — детерминированный оркестратор: выполняет **7 шагов**
(проверочный + 6 рабочих) через HTTP-сток и возвращает срез состояния программы
в заданной точке останова.

Контракты каждого HTTP-вызова: [docs/MCP-HTTP/README.md](../MCP-HTTP/README.md)

```
stdin / args
     │
     ▼
┌───────────────────────────────────────┐
│  tracer-sink breakpoint-metrics         │
│                                         │
│  [Ш.0] GET  /mcp/capabilities           │
│  [Ш.0.5] Валидация JSON                  │
│  [Ш.1] set_breakpoint  → session_id   │
│  [Ш.2] resume                           │
│  [Ш.3] poll_break_status (loop 250ms)  │
│  [Ш.4] get_call_stack  → frameId       │
│  [Ш.5] get_variables   (filter на CLI) │
│  [Ш.6] suspend         (ошибка OK)    │
│  JSON → stdout, exit code                │
└───────────────────────────────────────┘
                 │ 7 HTTP-вызовов
                 ▼
  MCP Server :18080/mcp  (и GET /mcp/capabilities)
```

---

## 2. Интерфейс командной строки

### 2.1 Синтаксис

```
tracer-sink breakpoint-metrics [OPTIONS] [JSON]
```

| Форма вызова | Описание |
|---|---|
| `tracer-sink breakpoint-metrics '{"project":"...","file":"...","line":42}'` | JSON аргументом |
| `echo '{...}' \| tracer-sink breakpoint-metrics` | JSON из stdin |
| `tracer-sink breakpoint-metrics --help` | Справка по методу |

### 2.2 Опции

| Опция | Описание |
|---|---|
| `--host HOST` | Адрес HTTP-сервера (по умолчанию: `localhost`) |
| `--port PORT` | Порт HTTP-сервера (по умолчанию: `18080`) |
| `--poll-interval MS` | Интервал опроса в мс (по умолчанию: `250`) |
| `--help` | Справка по методу |

---

## 3. Входной контракт (BreakpointMetricsRequest)

Входной JSON валидируется **до** любых HTTP-обращений. При ошибке — немедленно exit 2.

### 3.1 Поля

| Поле | Тип | Обязательное | По умолчанию | Описание |
|------|-----|:---:|---|---|
| `project` | string | ✅ | — | Имя проекта 1C в EDT workspace |
| `file` | string | ✅ | — | Путь к файлу относительно корня проекта |
| `line` | integer > 0 | ✅ | — | Номер строки точки останова |
| `variables` | string[] | — | `[]` | Имена переменных (пусто = все доступные) |
| `mainClass` | string | — | `""` | Точка входа |
| `args` | string | — | `""` | Аргументы запуска |
| `timeoutMs` | integer > 0 | — | `30000` | Общий таймаут ожидания точки останова |

### 3.2 Правила валидации

- `project` — непустая строка → exit 2.
- `file` — непустая строка → exit 2.
- `line` — целое число > 0 → exit 2.
- `variables` — массив строк (может быть пустым) → exit 2 если не массив.
- `timeoutMs` — целое число > 0 → exit 2.
- JSON должен быть синтаксически корректным → exit 2.

### 3.3 Пример

```json
{
  "project": "smallbase",
  "file": "CommonModules/ПроверкаРезультата/Module.bsl",
  "line": 42,
  "variables": ["Результат", "ТекущийОбъект", "Ошибка"],
  "mainClass": "ОбщийМодуль.ПроверкаРезультата",
  "timeoutMs": 60000
}
```

---

## 4. Выходной контракт (BreakpointMetricsResult)

Результат пишется в `stdout` одной JSON-строкой.

### 4.1 Успех (exit 0)

```json
{
  "ok": true,
  "session_id": "sess_20260606_220000_xyz",
  "hit": true,
  "file": "CommonModules/ПроверкаРезультата/Module.bsl",
  "line": 42,
  "durationMs": 4210,
  "callStack": [
    "ОбщийМодуль.ПроверкаРезультата:42",
    "ОбщийМодуль.Основной:15"
  ],
  "variables": {
    "Результат": "Истина",
    "ТекущийОбъект": "Справочник.Номенклатура",
    "Ошибка": "Неопределенно"
  }
}
```

### 4.2 Ошибка (exit 1–4)

```json
{
  "ok": false,
  "error": "poll_break_status: timeout 60000ms exceeded",
  "exit_code": 3
}
```

---

## 5. Таксономия exit-кодов

| Код | Константа | Условие | Действие агента |
|-----|-----------|---------|----------------|
| `0` | `SUCCESS` | Точка достигнута, срез собран | Читать `variables`, `callStack` |
| `1` | `NETWORK_ERROR` | HTTP недоступен / метод не реализован | Проверить MCP Server; если сервер готов — ждать реализации метода |
| `2` | `CONTRACT_ERROR` | Невалидный JSON или обязательные поля | Исправить входной JSON |
| `3` | `RUNTIME_ERROR` | Точка не достигнута за `timeoutMs`, отсутствующий метод | Проверить путь, строку, дождаться реализации в HTTP-стоке |
| `4` | `INTERNAL_ERROR` | Неожиданное исключение CLI | Сообщить разработчику |

---

## 6. Алгоритм оркестрации

```
1.  Разобрать аргументы
2.  --help → вывести справку, exit 0

[ШАГ 0] GET /mcp/capabilities
    └── ошибка сети → exit 1
    └── set_breakpoint отсутствует в methods[] →
        stdout: {"ok":false,"error":"set_breakpoint не реализован"}, exit 3
    └── poll_break_status отсутствует →
        stdout: {"ok":false,"error":"poll_break_status не реализован"}, exit 3

[ШАГ 0.5] Разобрать JSON из аргумента / stdin
    └── ошибка парсинга → stdout: {"ok":false,...}, exit 2
    └── ошибка валидации (§3.2) → stdout: {"ok":false,...}, exit 2

[ШАГ 1] set_breakpoint(project, file, line)
    └── session_id ← из ответа
    └── ошибка сети → exit 1
    └── ошибка JSON-RPC → exit 3

[ШАГ 2] resume(project, threadId)
    └── threadId ← из debug_status
    └── ошибка JSON-RPC → exit 3

[ШАГ 3] polling loop: poll_break_status(project, session_id)
    deadline = now() + timeoutMs
    while now() < deadline:
        response = POST /mcp {poll_break_status, session_id}
        if network_error: sleep(pollIntervalMs); continue  # до 3 раз
        if suspended == true:
            threadId ← response.threadId
            break
        sleep(pollIntervalMs)
    if deadline прошёл:
        stdout: {"ok":false,"error":"timeout"}, exit 3

[ШАГ 4] get_call_stack(project, threadId, session_id)
    └── frameId ← frames[0].frameId
    └── frames пусты → exit 3 «пустой стек»

[ШАГ 5] get_variables(project, threadId, frameId, session_id)
    └── если variables[] != [] — оркестратор фильтрует ответ по перечню имён
    └── HTTP возвращает все переменные фрейма — фильтрация на стороне CLI

[ШАГ 6] suspend(project, threadId, session_id)
    └── ошибка не блокирует exit 0 — данные уже собраны

stdout: {ok, session_id, hit, file, line, durationMs, callStack, variables}
exit 0

9. Непредвиденное исключение → exit 4
```

---

## 7. Нефункциональные требования

| Требование | Значение |
|---|---|
| Время старта CLI (без HTTP) | < 200 мс |
| Зависимости | Только stdlib Python 3.8+ |
| Совместимость ОС | Linux, macOS, Windows |
| Интервал опроса по умолчанию | 250 мс |
| Максимум повторов при сетевой ошибке | 3 раза |

---

## 8. Тестовые сценарии

| ID | Сценарий | Входные данные | Ожидаемый exit |
|----|----------|---------------|---------------|
| T-01 | Точка достигнута, переменные собраны | Валидный JSON, все методы есть | 0 |
| T-02 | Сервер не запущен | Валидный JSON, порт закрыт | 1 |
| T-03 | `set_breakpoint` отсутствует в capabilities | Валидный JSON, сервер готов | 3 |
| T-04 | `poll_break_status` отсутствует в capabilities | Валидный JSON, сервер готов | 3 |
| T-05 | Пустой `project` | `{"project":"","file":"X","line":1}` | 2 |
| T-06 | Отсутствует `file` | `{"project":"X","line":1}` | 2 |
| T-07 | `line` = 0 | `{"project":"X","file":"X","line":0}` | 2 |
| T-08 | `variables` не массив | `{...,"variables":"Результат"}` | 2 |
| T-09 | Невалидный JSON | `{project:X}` | 2 |
| T-10 | Точка не достигнута (таймаут) | Валидный JSON, точка на недостижимой строке | 3 |
| T-11 | Разрыв сети на шаге 3 (поллинг), восстановление | Сервер возобновляется в течение 3 попыток | 0 |
| T-12 | Фильтрация переменных | `variables=["X"]`, в ответе X и Y | 0, в `variables` только X |
| T-13 | --help | — | 0 |
| T-14 | JSON из stdin | `echo '{...}' \| tracer-sink breakpoint-metrics` | 0 |
