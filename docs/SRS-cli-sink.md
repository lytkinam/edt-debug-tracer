# SRS — EDT Debug Tracer: CLI-Сток

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Репозиторий:** lytkinam/edt-debug-tracer, ветка `feature/trace-sink`  
**Связанный документ:** docs/BRD-cli-sink.md  
**Статус:** Draft

---

## 1. Обзор системы

CLI-сток — это исполняемый скрипт `scripts/tracer-sink` (Python 3.8+),
который реализует детерминированный оркестратор единственного сценария:
полного цикла отладочного трейса через `POST /sink/run` на HTTP-сервере
плагина EDT Debug Tracer.

```
stdin / args
     │
     ▼
┌────────────────────────────────────┐
│  tracer-sink  (scripts/tracer-sink)│
│                                    │
│  1. --help / --version             │
│  2. Валидация JSON-контракта       │
│  3. POST /sink/run                 │
│  4. Маппинг ответа → exit code     │
│  5. JSON-результат в stdout        │
└────────────────┬───────────────────┘
                 │ HTTP POST
                 ▼
     TestActivator:18080/sink/run
                 │
                 ▼
         TracerSink.run()
```

---

## 2. Интерфейс командной строки

### 2.1 Синтаксис

```
tracer-sink [OPTIONS] [JSON]
```

| Форма вызова | Описание |
|---|---|
| `tracer-sink '{"project":"..."}'` | JSON передаётся аргументом |
| `echo '{...}' \| tracer-sink` | JSON читается из stdin |
| `tracer-sink --help` | Вывод справки и описания контракта |
| `tracer-sink --version` | Вывод версии (например, `tracer-sink 1.0.0`) |

Приоритет: аргумент > stdin.

### 2.2 Опции

| Опция | Описание |
|---|---|
| `--host HOST` | Адрес HTTP-сервера (по умолчанию: `localhost`) |
| `--port PORT` | Порт HTTP-сервера (по умолчанию: `18080`) |
| `--token TOKEN` | Bearer-токен авторизации (если сервер требует) |
| `--timeout SEC` | Таймаут HTTP-запроса в секундах (по умолчанию: `120`) |
| `--help` | Справка |
| `--version` | Версия |

---

## 3. Входной контракт (SinkRequest)

Входной JSON обязан пройти валидацию **до** обращения к HTTP-серверу.
При провале валидации — немедленно exit 2.

### 3.1 Поля

| Поле | Тип | Обязательное | По умолчанию | Описание |
|------|-----|:---:|---|---|
| `project` | string | ✓ | — | Имя проекта 1C в EDT workspace |
| `mainClass` | string | — | `""` | Точка входа (класс или модуль) |
| `args` | string | — | `""` | Аргументы запуска |
| `maxSteps` | integer ≥ 0 | — | `0` | Лимит шагов (0 = безлимит) |
| `stepType` | `"into"` \| `"over"` \| `"return"` | — | `"into"` | Тип шага отладчика |
| `saveJson` | boolean | — | `true` | Сохранять ли JSON на диск |
| `timeoutMs` | integer > 0 | — | `30000` | Таймаут сессии в мс |

### 3.2 Правила валидации

- `project` — непустая строка. Отсутствие или пустое значение → exit 2.
- `maxSteps` — целое число ≥ 0. Отрицательное → exit 2.
- `stepType` — одно из `"into"`, `"over"`, `"return"`. Иное → exit 2.
- `timeoutMs` — целое число > 0. Ноль или отрицательное → exit 2.
- JSON должен быть синтаксически корректным. Ошибка парсинга → exit 2.

### 3.3 Пример входного JSON

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

## 4. Выходной контракт (SinkResult)

Результат всегда пишется в `stdout` в виде одной JSON-строки.
`stderr` используется только для диагностических сообщений (не для агента).

### 4.1 Поля успешного ответа (exit 0)

```json
{
  "ok": true,
  "session_id": "sess_20260606_214500_abc",
  "totalSteps": 187,
  "durationMs": 12340,
  "json_path": "/workspace/.edt-debug-tracer/sink_smallbase_1749225900.json",
  "steps": [ "..." ]
}
```

### 4.2 Поля ответа с ошибкой (exit 1–4)

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
| `1` | `NETWORK_ERROR` | HTTP недоступен, timeout соединения | Проверить, запущен ли сервер |
| `2` | `CONTRACT_ERROR` | Невалидный JSON, отсутствует `project`, неверный тип поля | Исправить входной JSON |
| `3` | `RUNTIME_ERROR` | Сервер вернул `ok: false` (ошибка запуска, таймаут сессии) | Проверить параметры запуска |
| `4` | `INTERNAL_ERROR` | Неожиданное исключение внутри CLI | Сообщить разработчику |

---

## 6. Поведение --help

`--help` выводит в stdout валидный текст, пригодный для чтения агентом:

```
tracer-sink 1.0.0
Детерминированный оркестратор трейса EDT Debug Tracer.

ИСПОЛЬЗОВАНИЕ:
  tracer-sink [OPTIONS] [JSON]
  echo '{...}' | tracer-sink [OPTIONS]

ОБЯЗАТЕЛЬНЫЕ ПОЛЯ JSON:
  project       string   Имя проекта в EDT workspace

ОПЦИОНАЛЬНЫЕ ПОЛЯ JSON:
  mainClass     string   Точка входа (по умолчанию: "")
  args          string   Аргументы запуска (по умолчанию: "")
  maxSteps      int≥0    Лимит шагов, 0=безлимит (по умолчанию: 0)
  stepType      string   into|over|return (по умолчанию: "into")
  saveJson      bool     Сохранить JSON на диск (по умолчанию: true)
  timeoutMs     int>0    Таймаут сессии в мс (по умолчанию: 30000)

EXIT CODES:
  0  SUCCESS        — трейс завершён
  1  NETWORK_ERROR  — HTTP-сервер недоступен
  2  CONTRACT_ERROR — невалидный входной JSON
  3  RUNTIME_ERROR  — ошибка выполнения сессии
  4  INTERNAL_ERROR — внутренняя ошибка CLI

OPTIONS:
  --host HOST    Адрес сервера (по умолчанию: localhost)
  --port PORT    Порт сервера (по умолчанию: 18080)
  --token TOKEN  Bearer-токен авторизации
  --timeout SEC  Таймаут HTTP-запроса в секундах (по умолчанию: 120)
  --help         Эта справка
  --version      Версия CLI
```

---

## 7. Структура файла

```
scripts/
└── tracer-sink          # исполняемый Python-скрипт (chmod +x)
```

Скрипт — один файл. Без зависимостей вне стандартной библиотеки Python 3.8+
(`json`, `sys`, `argparse`, `urllib.request`).

---

## 8. Алгоритм выполнения

```
1. Разобрать аргументы (argparse)
2. Если --help → вывести справку, exit 0
3. Если --version → вывести версию, exit 0
4. Прочитать JSON из аргумента или stdin
5. Распарсить JSON
   └── ошибка парсинга → stdout: {"ok":false,"error":"..."}, exit 2
6. Валидировать поля по контракту (§3.2)
   └── ошибка → stdout: {"ok":false,"error":"..."}, exit 2
7. Выполнить POST http://{host}:{port}/sink/run
   ├── ошибка соединения → stdout: {"ok":false,...}, exit 1
   └── таймаут соединения → stdout: {"ok":false,...}, exit 1
8. Разобрать JSON-ответ сервера
   ├── ok==true  → stdout: ответ сервера + "exit_code":0, exit 0
   └── ok==false → stdout: ответ сервера + "exit_code":3, exit 3
9. При любом непредвиденном исключении → stdout: {"ok":false,...}, exit 4
```

---

## 9. Нефункциональные требования

| Требование | Значение |
|---|---|
| Время старта CLI | < 200 мс (без учёта HTTP) |
| Зависимости | Только стандартная библиотека Python 3.8+ |
| Совместимость ОС | Linux, macOS, Windows (через `python tracer-sink`) |
| Размер скрипта | < 300 строк |
| Покрытие тестами | Unit-тесты для валидации контракта и маппинга exit-кодов |

---

## 10. Тестовые сценарии

| ID | Сценарий | Входные данные | Ожидаемый exit |
|----|----------|---------------|---------------|
| T-01 | Успешный трейс | Валидный JSON, сервер доступен | 0 |
| T-02 | Сервер не запущен | Валидный JSON, порт закрыт | 1 |
| T-03 | Пустой project | `{"project":""}` | 2 |
| T-04 | Отсутствует project | `{"mainClass":"X"}` | 2 |
| T-05 | Неверный stepType | `{"project":"X","stepType":"jump"}` | 2 |
| T-06 | Невалидный JSON | `{project: X}` (без кавычек) | 2 |
| T-07 | Сервер вернул ok:false | Валидный JSON, EDT не запущен | 3 |
| T-08 | --help | — | 0 |
| T-09 | --version | — | 0 |
| T-10 | JSON из stdin | `echo '{"project":"X"}' \| tracer-sink` | 0 |
