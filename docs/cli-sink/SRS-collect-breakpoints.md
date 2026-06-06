# SRS — CLI-Сток: метод `collect-breakpoints`

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Репозиторий:** lytkinam/edt-debug-tracer, ветка `feature/trace-sink`  
**Связанный документ:** docs/cli-sink/BRD-collect-breakpoints.md  
**Статус:** Draft

---

## Потребители контракта

| Компонент | Роль | Как использует |
|---|---|---|
| ИИ-агент / пользователь CLI | Потребитель | Вызывает `tracer-sink collect-breakpoints`, читает stdout JSON, реагирует на exit code |
| HTTP-сток (Eclipse-плагин) | Поставщик | Реализует `start(collect_breakpoints)`, `get_collect_breakpoints`, `get_session_status` |

---

## 1. Обзор

Метод `collect-breakpoints` — детерминированный оркестратор: выполняет
**9 шагов** над HTTP-стоком, собирает снимки состояния по всем точкам останова
за **один прогон** программы и возвращает результат.

Движение по коду управляется механизмом `ISuspendListener` Eclipse Platform Debug.
Оркестратор **не управляет паузами между остановками** — он запускает программу,
ждёт завершения сессии и забирает накопленные снимки.

```
stdin / args
     │
     ▼
┌─────────────────────────────────────────────┐
│  tracer-sink collect-breakpoints            │
│                                             │
│  [Ш.0] GET  /mcp/capabilities               │
│  [Ш.0.5] Валидация JSON                     │
│  [Ш.1] list_breakpoints  → очистить чужие   │
│  [Ш.2] set_breakpoint × N  → session_ids[]  │
│  [Ш.3] start(collect_breakpoints=true)      │
│         → run_session_id                    │
│  [Ш.4] poll_session_status (loop 500ms)     │
│  [Ш.5] get_collect_breakpoints(session_id)  │
│  [Ш.6] remove_breakpoint × N  (очистка)    │
│  JSON → stdout, exit code                   │
└─────────────────────────────────────────────┘
                 │ HTTP-вызовы
                 ▼
    MCP Server :18080/mcp
                 │
    ┌────────────┴──────────────┐
    │ ISuspendListener (Eclipse)│
    │  SUSPEND+BREAKPOINT →     │
    │  capture() + resume()     │
    └───────────────────────────┘
```

---

## 2. Интерфейс командной строки

### 2.1 Синтаксис

```
tracer-sink collect-breakpoints [OPTIONS] [JSON]
```

| Форма вызова | Описание |
|---|---|
| `tracer-sink collect-breakpoints '{"project":"...","breakpoints":[...]}'` | JSON аргументом |
| `echo '{...}' \| tracer-sink collect-breakpoints` | JSON из stdin |
| `tracer-sink collect-breakpoints --help` | Справка по методу |

### 2.2 Опции

| Опция | Описание |
|---|---|
| `--host HOST` | Адрес HTTP-сервера (по умолчанию: `localhost`) |
| `--port PORT` | Порт HTTP-сервера (по умолчанию: `18080`) |
| `--poll-interval MS` | Интервал опроса `poll_session_status` в мс (по умолчанию: `500`) |
| `--help` | Справка |

---

## 3. Входной контракт (CollectBreakpointsRequest)

Входной JSON валидируется **до** любых HTTP-вызовов. Ошибка → exit 2.

### 3.1 Поля

| Поле | Тип | Обязательное | По умолчанию | Описание |
|------|-----|:---:|---|---|
| `project` | string | ✓ | — | Имя проекта 1C в EDT workspace |
| `breakpoints` | BreakpointSpec[] | ✓ | — | Список точек останова (≥ 1) |
| `mainClass` | string | — | `""` | Точка входа |
| `args` | string | — | `""` | Аргументы запуска |
| `variables` | string[] | — | `[]` | Переменные для сбора (пусто = все; применяется ко всем точкам) |
| `timeoutMs` | integer > 0 | — | `60000` | Таймаут ожидания завершения сессии |

### 3.2 BreakpointSpec

| Поле | Тип | Обязательное | Описание |
|------|-----|:---:|---|
| `file` | string | ✓ | Путь к файлу относительно корня проекта |
| `line` | integer > 0 | ✓ | Номер строки |
| `variables` | string[] | — | Переопределение фильтра для конкретной точки |

### 3.3 Правила валидации

- `project` — непустая строка → exit 2.
- `breakpoints` — массив ≥ 1 элемента → exit 2.
- Каждый `BreakpointSpec`: `file` непустой, `line > 0` → exit 2.
- `timeoutMs` — целое число > 0 → exit 2.
- JSON синтаксически корректен → exit 2.

### 3.4 Пример

```json
{
  "project": "smallbase",
  "mainClass": "ОбщийМодуль.ПроверкаРезультата",
  "breakpoints": [
    {
      "file": "CommonModules/ПроверкаРезультата/Module.bsl",
      "line": 42,
      "variables": ["Результат", "ТекущийОбъект"]
    },
    {
      "file": "CommonModules/Вспомогательный/Module.bsl",
      "line": 17
    }
  ],
  "timeoutMs": 90000
}
```

---

## 4. Выходной контракт (CollectBreakpointsResult)

### 4.1 Успех (exit 0)

```json
{
  "ok": true,
  "session_id": "sess_20260606_230000_xyz",
  "durationMs": 8340,
  "breakpoints": [
    {
      "file": "CommonModules/ПроверкаРезультата/Module.bsl",
      "line": 42,
      "hits": [
        {
          "hitIndex": 0,
          "timestampMs": 1749247203210,
          "callStack": [
            "ОбщийМодуль.ПроверкаРезультата:42",
            "ОбщийМодуль.Основной:15"
          ],
          "variables": {
            "Результат": "Истина",
            "ТекущийОбъект": "Справочник.Номенклатура"
          }
        }
      ]
    },
    {
      "file": "CommonModules/Вспомогательный/Module.bsl",
      "line": 17,
      "hits": []
    }
  ]
}
```

### 4.2 Ошибка (exit 1–4)

```json
{
  "ok": false,
  "error": "poll_session_status: timeout 90000ms exceeded",
  "exit_code": 3,
  "partial_breakpoints": [...]
}
```

> `partial_breakpoints` — снимки, успевшие накопиться до таймаута (может быть пустым).

---

## 5. Таксономия exit-кодов

| Код | Константа | Условие | Действие агента |
|-----|-----------|---------|----------------|
| `0` | `SUCCESS` | Сессия завершена, снимки собраны | Читать `breakpoints[].hits` |
| `1` | `NETWORK_ERROR` | HTTP недоступен / метод не реализован | Проверить сервер; при отсутствии `collect_breakpoints` в capabilities — ждать реализации |
| `2` | `CONTRACT_ERROR` | Невалидный JSON или поля | Исправить входной JSON |
| `3` | `RUNTIME_ERROR` | Сессия не завершилась за `timeoutMs`; EDT не запущен | Проверить EDT, `project`, точки, таймаут |
| `4` | `INTERNAL_ERROR` | Неожиданное исключение CLI | Сообщить разработчику |

---

## 6. Алгоритм оркестрации

```
1. Разобрать аргументы
2. --help → вывести справку, exit 0

[ШАГ 0] GET /mcp/capabilities
    └── ошибка сети → exit 1
    └── "collect_breakpoints" отсутствует в start.params[]
        → stdout: {"ok":false,"error":"collect_breakpoints не реализован"}, exit 1
    └── "get_collect_breakpoints" отсутствует в methods[]
        → stdout: {"ok":false,"error":"get_collect_breakpoints не реализован"}, exit 1

[ШАГ 0.5] Разобрать JSON из аргумента / stdin
    └── ошибка парсинга / валидации → stdout: {"ok":false,...}, exit 2

[ШАГ 1] list_breakpoints(project)
    Получить список активных точек.
    Для каждой точки не из нашего запроса:
        remove_breakpoint(project, file, line)  # toggle: повторный set снимает
    └── ошибка сети → exit 1

[ШАГ 2] Для каждого breakpoints[i]:
    set_breakpoint(project, file, line)
    bp_ids[i] ← ответ.bp_id
    └── ошибка → exit 1

[ШАГ 3] start(project, mainClass, args, collect_breakpoints=true)
    run_session_id ← ответ.session_id
    └── ошибка сети → exit 1
    └── ошибка JSON-RPC → exit 3

[ШАГ 4] polling loop: poll_session_status(run_session_id)
    deadline = now() + timeoutMs
    while now() < deadline:
        resp = POST /mcp {poll_session_status, run_session_id}
        if network_error: sleep(pollIntervalMs); continue  # до 3 раз
        if resp.status in ["FINISHED", "TERMINATED", "ERROR"]:
            break
        sleep(pollIntervalMs)
    if deadline прошёл:
        → [ШАГ 6 — очистка], затем exit 3

[ШАГ 5] get_collect_breakpoints(run_session_id)
    data ← ответ.breakpoints[]  # список снимков по каждой точке
    └── ошибка сети → exit 1 (данные потеряны)

[ШАГ 6] Очистка: для каждого bp_ids[i]:
    remove_breakpoint(project, file, line)
    └── ошибка не блокирует exit 0 — логируется в stderr

stdout: {ok, session_id, durationMs, breakpoints[].hits}
exit 0

9. Непредвиденное исключение → exit 4
```

### Важно: toggle-семантика set_breakpoint

> Повторный вызов `set_breakpoint(file, line)` на **уже установленной** точке
> **снимает** её. Поэтому [ШАГ 1] обязан удалить чужие точки через
> `remove_breakpoint`, а не через повторный `set_breakpoint`.

---

## 7. HTTP-методы, используемые оркестратором

| Шаг | Метод | Новый? | Описание |
|-----|-------|:------:|---|
| 0 | `GET /mcp/capabilities` | Нет | Проверка наличия `collect_breakpoints` |
| 1 | `list_breakpoints` | Нет | Список активных точек |
| 1 | `remove_breakpoint` | Нет | Снятие чужой точки |
| 2 | `set_breakpoint` | Нет | Установка точки, возврат `bp_id` |
| 3 | `start` | **Расширен** | Добавлен параметр `collect_breakpoints: bool` |
| 4 | `poll_session_status` | **Новый** | Ожидание завершения сессии (не `poll_break_status`) |
| 5 | `get_collect_breakpoints` | **Новый** | Возврат накопленных снимков по `session_id` |
| 6 | `remove_breakpoint` | Нет | Очистка своих точек |

> `resume` с `collect_breakpoints=true` используется аналогично `start` если
> сессия уже приостановлена: передаёт управление `ISuspendListener`.

---

## 8. Нефункциональные требования

| Требование | Значение |
|---|---|
| Время старта CLI (без HTTP) | < 200 мс |
| Зависимости | Только stdlib Python 3.8+ |
| Совместимость ОС | Linux, macOS, Windows |
| Интервал опроса по умолчанию | 500 мс (`poll_session_status`) |
| Максимум повторов при сетевой ошибке | 3 раза |

---

## 9. Тестовые сценарии

| ID | Сценарий | Входные данные | Ожидаемый exit |
|----|----------|---------------|---------------|
| T-01 | Обе точки сработали | Валидный JSON, EDT запущен, точки достижимы | 0 |
| T-02 | Одна точка не сработала | Точка на недостижимой строке | 0, `hits:[]` для неё |
| T-03 | Сервер не запущен | Валидный JSON, порт закрыт | 1 |
| T-04 | `collect_breakpoints` нет в capabilities | Сервер без поддержки | 1 |
| T-05 | `get_collect_breakpoints` нет в capabilities | Сервер без поддержки | 1 |
| T-06 | Пустой `project` | `{"project":"","breakpoints":[...]}` | 2 |
| T-07 | `breakpoints: []` | `{"project":"X","breakpoints":[]}` | 2 |
| T-08 | `line: 0` в точке | `{...,"breakpoints":[{"file":"X","line":0}]}` | 2 |
| T-09 | Невалидный JSON | `{project: X}` | 2 |
| T-10 | Таймаут | Валидный JSON, программа зависла | 3, `partial_breakpoints` в ответе |
| T-11 | Чужие точки очищены | В EDT есть активные чужие точки | 0, чужие точки сняты |
| T-12 | Toggle-защита | Нет повторного `set_breakpoint` для снятия | 0, `remove_breakpoint` в шаге 1 |
| T-13 | Очистка не блокирует успех | `remove_breakpoint` на шаге 6 падает | 0, ошибка в stderr |
| T-14 | JSON из stdin | `echo '{...}' \| tracer-sink collect-breakpoints` | 0 |
| T-15 | --help | — | 0 |
