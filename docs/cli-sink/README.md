# docs/cli-sink — Контракты CLI-стока

**Версия стандарта:** 1.0  
**Ветка:** `feature/trace-sink`

---

## Роли

| Роль | Компонент | Ответственность |
|---|---|---|
| **Оркестратор** | `tracer-sink` (CLI) | Владеет сценарием: последовательность HTTP-вызовов, обработка ошибок, выходной JSON, exit code |
| **Исполнитель** | HTTP-сток (`MCP Server`) | Выполняет один атомарный вызов отладчика за раз, возвращает детерминированный ответ |
| **Потребитель** | ИИ-агент / персонал | Читает stdout JSON, реагирует на exit code |

Оркестратор **не знает** о внутренности плагина.  
Исполнитель **не знает** о сценарии — он исполняет только один метод за вызов.

---

## Стандарт документации метода

Каждый метод (subcommand) CLI-стока описывается парой документов:

| Документ | Назначение |
|---|---|
| `BRD-{subcommand}.md` | **Зачем** метод: бизнес-проблема, цель, что агент ожидает получить |
| `SRS-{subcommand}.md` | **Как** метод работает: CLI-интерфейс, входной/выходной JSON-контракт, оркестрация HTTP-вызовов, exit codes |

---

## Методы CLI-стока

| Subcommand | Статус | Описание | HTTP-вызовов | Документы |
|---|:---:|---|:---:|---|
| `trace-session` | ✅ готов | Полный цикл трейса | 1 (`POST /sink/run`) | [BRD](BRD-trace-session.md) · [SRS](SRS-trace-session.md) |
| `breakpoint-metrics` | ⚠️ требует `set_breakpoint` | Срез состояния в точке останова | 6 (`POST /mcp`) | [BRD](BRD-breakpoint-metrics.md) · [SRS](SRS-breakpoint-metrics.md) |

> ⚠️ `breakpoint-metrics` заблокирован до реализации `set_breakpoint` в HTTP-стоке  
> (см. [docs/MCP-HTTP/BRD-set_breakpoint.md](../MCP-HTTP/BRD-set_breakpoint.md)).

---

## Оркестрация `breakpoint-metrics`

Оркестратор выполняет **6 последовательных HTTP-вызова** через `POST /mcp`.
Каждый вызов атомарен: HTTP-сток не знает о сценарии.

```
 ВХОД: {project, file, line, variables[], timeoutMs}
      │
      ▼
[ШАГ 0] Валидация JSON-контракта          → exit 2 при ошибке
      │
      ▼
[ШАГ 1] set_breakpoint(project, file, line) → exit 3 при ошибке
      │                                          → exit 1 если сеть недоступна
      ▼
[ШАГ 2] resume(project, threadId)          → exit 3 при ошибке
      │  threadId ← из debug_status
      ▼
[ШАГ 3] wait_for_break(project, timeoutMs) → exit 3 если timeout: true
      │  threadId ← из ответа
      ▼
[ШАГ 4] get_call_stack(project, threadId)  → exit 3 если frames пусты
      │  frameId ← frames[0].frameId
      ▼
[ШАГ 5] get_variables(project, threadId, frameId)
      │  фильтрация по variables[] — на стороне оркестратора
      ▼
[ШАГ 6] suspend(project, threadId)         → ошибка не блокирует exit 0
      │
      ▼
  stdout: {ok, session_id, hit, file, line, durationMs, callStack, variables}
  exit 0
```

Контракты HTTP-вызовов: [docs/MCP-HTTP/README.md](../MCP-HTTP/README.md)

---

## Оркестрация `trace-session`

Оркестратор выполняет **1 HTTP-вызов** — вся логика трейса на стороне Java-компонента.

```
 ВХОД: {project, mainClass, args, maxSteps, stepType, saveJson, timeoutMs}
      │
      ▼
[ШАГ 0] Валидация JSON-контракта     → exit 2 при ошибке
      │
      ▼
[ШАГ 1] POST /sink/run                  → exit 1 если сеть недоступна
      │
      ▼
  stdout: {ok, session_id, steps[], json_path, ...}
  exit 0 | exit 3
```

---

## Общий контракт CLI-стока

### Транспорт

```bash
# JSON аргументом
tracer-sink <subcommand> [OPTIONS] '{...json...}'

# JSON через stdin
echo '{...}' | tracer-sink <subcommand> [OPTIONS]
```

### Exit codes (едины для всех subcommands)

| Code | Константа | Условие | Действие агента |
|---|---|---|---|
| 0 | SUCCESS | Сценарий завершён | Читать stdout JSON |
| 1 | NETWORK_ERROR | HTTP-сервер недоступен | Проверить, запущен ли MCP Server |
| 2 | CONTRACT_ERROR | Невалидный входной JSON | Исправить поля входа |
| 3 | RUNTIME_ERROR | Ошибка выполнения сценария | Читать `error` в stdout |
| 4 | INTERNAL_ERROR | Непредвиденное исключение CLI | Сообщить разработчику |

### Общий формат stdout при ошибке

```json
{"ok": false, "error": "<сообщение>", "exit_code": <1|2|3|4>}
```
