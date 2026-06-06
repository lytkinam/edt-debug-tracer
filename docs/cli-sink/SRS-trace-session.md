# SRS — CLI-Сток: метод `trace-session`

**Версия:** 1.1  
**Дата:** 2026-06-06  
**Репозиторий:** lytkinam/edt-debug-tracer, ветка `feature/trace-sink`  
**Связанный документ:** docs/cli-sink/BRD-trace-session.md  
**Статус:** Draft

---

## 1. Обзор

Метод `trace-session` — это команда CLI-стока `tracer-sink`, которая реализует
детерминированный оркестратор полного цикла отладочного трейса через
`POST /sink/run` на HTTP-сервере плагина EDT Debug Tracer.

```
stdin / args
     │
     ▼
┌────────────────────────────────────┐
│  tracer-sink trace-session         │
│                                    │
│  1. Валидация JSON-контракта       │
│  2. POST /sink/run                 │
│  3. Маппинг ответа → exit code     │
│  4. JSON-результат в stdout        │
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
| `--token TOKEN` | Bearer-токен авторизации |
| `--timeout SEC` | Таймаут HTTP-запроса в секундах (по умолчанию: `120`) |
| `--help` | Справка по методу |

---

## 3. Входной контракт (TraceSessionRequest)

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

- `project` — непустая строка → exit 2 при нарушении.
- `maxSteps` — целое число ≥ 0 → exit 2 при нарушении.
- `stepType` — одно из `"into"`, `"over"`, `"return"` → exit 2 при нарушении.
- `timeoutMs` — целое число > 0 → exit 2 при нарушении.
- JSON должен быть синтаксически корректным → exit 2 при ошибке парсинга.

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

## 6. Алгоритм выполнения

```
1. Разобрать аргументы
2. --help → вывести справку, exit 0
3. Прочитать JSON из аргумента или stdin
4. Распарсить JSON
   └── ошибка → stdout: {"ok":false,"error":"..."}, exit 2
5. Валидировать поля (§3.2)
   └── ошибка → stdout: {"ok":false,"error":"..."}, exit 2
6. POST http://{host}:{port}/sink/run
   ├── ошибка соединения / таймаут → exit 1
   └── успех → разобрать ответ
7. ok==true  → stdout: ответ + "exit_code":0, exit 0
   ok==false → stdout: ответ + "exit_code":3, exit 3
8. Непредвиденное исключение → exit 4
```

---

## 7. Нефункциональные требования

| Требование | Значение |
|---|---|
| Время старта CLI | < 200 мс (без HTTP) |
| Зависимости | Только stdlib Python 3.8+ |
| Совместимость ОС | Linux, macOS, Windows |
| Размер скрипта | < 300 строк |

---

## 8. Тестовые сценарии

| ID | Сценарий | Входные данные | Ожидаемый exit |
|----|----------|---------------|---------------|
| T-01 | Успешный трейс | Валидный JSON, сервер доступен | 0 |
| T-02 | Сервер не запущен | Валидный JSON, порт закрыт | 1 |
| T-03 | Пустой project | `{"project":""}` | 2 |
| T-04 | Отсутствует project | `{"mainClass":"X"}` | 2 |
| T-05 | Неверный stepType | `{"project":"X","stepType":"jump"}` | 2 |
| T-06 | Невалидный JSON | `{project: X}` | 2 |
| T-07 | Сервер вернул ok:false | Валидный JSON, EDT не запущен | 3 |
| T-08 | --help | — | 0 |
| T-09 | JSON из stdin | `echo '{"project":"X"}' \| tracer-sink trace-session` | 0 |
