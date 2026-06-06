# SRS — CLI-Сток: метод `trace-session`

**Версия:** 1.2  
**Дата:** 2026-06-06  
**Репозиторий:** lytkinam/edt-debug-tracer, ветка `feature/trace-sink`  
**Связанный документ:** docs/cli-sink/BRD-trace-session.md  
**Статус:** Draft

---

## Потребители контракта

| Компонент | Роль | Как использует |
|---|---|---|
| ИИ-агент / пользователь | Потребитель | Вызывает `tracer-sink trace-session`, читает stdout JSON, реагирует на exit code |

---

## 1. Обзор

Метод `trace-session` — детерминированный оркестратор: выполняет **2 шага**
(валидация + один HTTP-вызов) и возвращает полный трейс сессии с всеми шагами.

Вся логика запуска, шагания и сбора находится внутри HTTP-стока (`/sink/run`).  
Оркестратор делает единственный вызов и маппирует ответ в exit code.

Контракт эндпоинта: [docs/MCP-HTTP/README.md](../MCP-HTTP/README.md) (раздел `trace-session`).

```
stdin / args
     │
     ▼
┌────────────────────────────────────┐
│  tracer-sink trace-session          │
│                                    │
│  [Ш.0] Валидация JSON             │
│  [Ш.1] POST /sink/run              │
│  ответ → exit code, stdout JSON  │
└────────────────────────────────────┘
                 │ 1 HTTP-вызов
                 ▼
      TestActivator :18080/sink/run
                 │
                 ▼
           TracerSink.run()
           (запуск, шагание, сбор шагов)
```

> **Принцип чёрного ящика:** оркестратор не знает, что происходит внутри `/sink/run`.
> Ему важно только: `ok: true` + `steps[]` + `json_path` в ответе.

---

## 2. Интерфейс командной строки

### 2.1 Синтаксис

```
tracer-sink trace-session [OPTIONS] [JSON]
```

| Форма вызова | Описание |
|---|---|
| `tracer-sink trace-session '{"project":"..."}'` | JSON передаётся аргументом |
| `echo '{...}' \| tracer-sink trace-session` | JSON читается из stdin |
| `tracer-sink trace-session --help` | Вывод справки |

### 2.2 Опции

| Опция | Описание |
|---|---|
| `--host HOST` | Адрес HTTP-сервера (по умолчанию: `localhost`) |
| `--port PORT` | Порт HTTP-сервера (по умолчанию: `18080`) |
| `--timeout SEC` | Таймаут HTTP-запроса в секундах (по умолчанию: `120`) |
| `--help` | Справка по методу |

---

## 3. Входной контракт (TraceSessionRequest)

Входной JSON валидируется **до** обращения к HTTP-серверу. При ошибке — немедленно exit 2.

### 3.1 Поля

| Поле | Тип | Обязательное | По умолчанию | Описание |
|------|-----|:---:|---|---|
| `project` | string | ✅ | — | Имя проекта 1C в EDT workspace |
| `mainClass` | string | — | `""` | Точка входа (класс или модуль) |
| `args` | string | — | `""` | Аргументы запуска |
| `maxSteps` | integer ≥ 0 | — | `0` | Лимит шагов (0 = безлимит) |
| `stepType` | `"into"` \| `"over"` \| `"return"` | — | `"into"` | Тип шага отладчика |
| `saveJson` | boolean | — | `true` | Сохранять ли JSON на диск |
| `timeoutMs` | integer > 0 | — | `30000` | Таймаут сессии в мс |

### 3.2 Правила валидации

- `project` — непустая строка → exit 2.
- `maxSteps` — целое число ≥ 0 → exit 2.
- `stepType` — одно из `"into"`, `"over"`, `"return"` → exit 2.
- `timeoutMs` — целое число > 0 → exit 2.
- JSON синтаксически корректен → exit 2.

### 3.3 Пример

```json
{
  "project": "smallbase",
  "mainClass": "ОбщийМодуль.ПроверкаРезультата",
  "maxSteps": 200,
  "stepType": "into",
  "saveJson": true,
  "timeoutMs": 60000
}
```

---

## 4. Выходной контракт (TraceSessionResult)

Результат пишется в `stdout` одной JSON-строкой.

### 4.1 Успех (exit 0)

```json
{
  "ok": true,
  "session_id": "sess_20260606_214500_abc",
  "totalSteps": 187,
  "durationMs": 12340,
  "json_path": "/workspace/.edt-debug-tracer/sink_smallbase_1749225900.json",
  "steps": ["..."]
}
```

### 4.2 Ошибка (exit 1–4)

```json
{
  "ok": false,
  "error": "connection refused: localhost:18080",
  "exit_code": 1
}
```

---

## 5. Таксономия exit-кодов

| Код | Константа | Условие | Действие агента |
|-----|-----------|---------|----------------|
| `0` | `SUCCESS` | Трейс завершён, `ok: true` | Читать `steps`, `json_path` |
| `1` | `NETWORK_ERROR` | HTTP недоступен / timeout | Проверить, запущен ли сервер |
| `2` | `CONTRACT_ERROR` | Невалидный JSON или поля | Исправить входной JSON |
| `3` | `RUNTIME_ERROR` | Сервер вернул `ok: false` | Проверить параметры запуска |
| `4` | `INTERNAL_ERROR` | Неожиданное исключение CLI | Сообщить разработчику |

---

## 6. Алгоритм оркестрации

```
1. Разобрать аргументы
2. --help → вывести справку, exit 0

[ШАГ 0] Разобрать JSON из аргумента / stdin
    └── ошибка парсинга   → stdout: {"ok":false,"error":"..."}, exit 2
    └── ошибка валидации (§3.2) → stdout: {"ok":false,"error":"..."}, exit 2

[ШАГ 1] POST http://{host}:{port}/sink/run
    └── ошибка сети / timeout       → stdout: {"ok":false,"error":"..."}, exit 1
    └── сервер вернул ok: false    → stdout: {ответ сервера + "exit_code":3}, exit 3
    └── сервер вернул ok: true     → stdout: {ответ сервера + "exit_code":0}, exit 0

3. Непредвиденное исключение → exit 4
```

> **Замечание:** таймаут HTTP-запроса (`--timeout SEC`) должен быть больше, чем `timeoutMs`
> во входном JSON + несколько секунд на накладные расходы (запуск, завершение, сериализация).
> Рекомендуемое соотношение: `--timeout` = `timeoutMs / 1000 + 30`.

---

## 7. Нефункциональные требования

| Требование | Значение |
|---|---|
| Время старта CLI (без HTTP) | < 200 мс |
| Зависимости | Только stdlib Python 3.8+ |
| Совместимость ОС | Linux, macOS, Windows |
| Размер скрипта | < 300 строк |

---

## 8. Тестовые сценарии

| ID | Сценарий | Входные данные | Ожидаемый exit |
|----|----------|---------------|---------------|
| T-01 | Успешный трейс | Валидный JSON, сервер доступен | 0 |
| T-02 | Сервер не запущен | Валидный JSON, порт закрыт | 1 |
| T-03 | Пустой `project` | `{"project":""}` | 2 |
| T-04 | Отсутствует `project` | `{"mainClass":"X"}` | 2 |
| T-05 | Неверный `stepType` | `{"project":"X","stepType":"jump"}` | 2 |
| T-06 | Невалидный JSON | `{project: X}` | 2 |
| T-07 | Сервер вернул `ok:false` | Валидный JSON, EDT не запущен | 3 |
| T-08 | HTTP-таймаут (`--timeout` выше `timeoutMs`) | `timeoutMs:60000`, `--timeout 90` | 1 или 3 |
| T-09 | --help | — | 0 |
| T-10 | JSON из stdin | `echo '{"project":"X"}' \| tracer-sink trace-session` | 0 |
