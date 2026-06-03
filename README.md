# EDT Debug Tracer

Eclipse OSGi-плагин для трассировки отладки 1С:EDT и Java-приложений. Перехватывает SUSPEND-события Eclipse Debug API, записывает позицию (процедура, строка, модуль, стек, переменные) и автоматически выполняет step-over/into для пошагового прохождения кода. Управляет debug-сессиями и breakpoints через собственный REST API.

## Возможности

- **Перехват debug-событий** через `IDebugEventSetListener` — pipeline auto-step без Thread creation
- **12 полей на каждый шаг**: procedure, line, module, thread_name, thread_id, ts, char_start, char_end, stack_depth, parent_seq, stack[], variables{}
- **SQLite + JSON** dual storage — запись в БД и файл одновременно
- **17 HTTP endpoints** — управление записью, debug-сессией, breakpoints, сессиями
- **Breakpoint management** — автоматическая расстановка точек останова по данным трейса (начало/конец каждой процедуры)
- **Debug session control** — launch, step, resume, terminate, stack trace через API
- **Фильтрация** — дедупликация, include/exclude модулей и процедур, лимиты записи
- **~130 конфигурируемых параметров** в `tracer.properties`
- **Post-processing** — call tree, dependency graph, hot spots, loop collapse (`scripts/analyze_trace.py`)

## Быстрый старт

### Установка

```bash
git clone https://github.com/lytkinam/edt-debug-tracer
cd edt-debug-tracer

# Собрать (Eclipse 2026-03)
EP="/opt/eclipse-latest/plugins"
CP="$EP/org.eclipse.osgi_*.jar:$EP/org.eclipse.core.runtime_*.jar:$EP/org.eclipse.equinox.common_*.jar:$EP/org.eclipse.debug.core_*.jar:$EP/org.eclipse.core.resources_*.jar:$EP/org.eclipse.core.jobs_*.jar"
javac --release 17 -d plugin/bin -cp "$CP" plugin/src/plugin17/test/*.java
jar cfm plugin17-test_1.0.0.jar plugin/META-INF/MANIFEST.MF -C plugin/bin .

# Установить
cp plugin17-test_1.0.0.jar /opt/eclipse-latest/plugins/
echo "plugin17-test,1.0.0.qualifier,plugins/plugin17-test_1.0.0.jar,4,true" \
  >> /opt/eclipse-latest/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info

# Перезапустить (БЕЗ -clean)
./scripts/eclipse-launch.sh restart
```

### Конфигурация

Файл `{workspace}/.edt-debug-tracer/tracer.properties`. Пример:

```properties
port=18060
storage.mode=both
storage.sqlite.path={workspace}/.edt-debug-tracer/tracer.db
capture.module.enabled=true
capture.variables.enabled=true
capture.callStack.enabled=true
autoStep.stepType=into
autoStep.defaultSteps=0
```

Полный файл конфигурации с комментариями на русском — ~130 параметров в 12 секциях: Server, Auth, Storage, SQLite, Capture (module/variables/thread/charPos/callStack), Dedup, Filters, Limits, Auto-step, Writer, Reliability, Logging.

| Instance | Workspace | Порт |
|----------|-----------|------|
| Eclipse 2026-03 (dev) | `/home/ai/workspace-eclipse-latest/` | 18060 |
| EDT 2025.1.5 (prod) | `/home/ai/workspace-edt2025/` | 18080 |

## API Reference

### Запись трейса

| Endpoint | Method | Параметры | Описание |
|----------|--------|-----------|----------|
| `/mcp/health` | GET | — | Проверка работоспособности |
| `/mcp/start` | POST | — | Начать запись трейса |
| `/mcp/run` | POST | `{"steps":100}` | Запустить auto-step (0 = безлимит) |
| `/mcp/stop` | POST | — | Остановить запись, сохранить в файл и SQLite |
| `/mcp/status` | GET | — | Статус: recording, autoStepping, entries, sqlite |

### Debug-сессия

| Endpoint | Method | Параметры | Описание |
|----------|--------|-----------|----------|
| `/mcp/debug/launch` | POST | `{"project":"...","mainClass":"..."}` | Запустить debug-сессию |
| `/mcp/debug/status` | GET | — | Состояние: running/suspended/terminated/none |
| `/mcp/debug/step` | POST | `{"type":"over"|"into"|"return"}` | Шаг отладки |
| `/mcp/debug/resume` | POST | — | Продолжить выполнение |
| `/mcp/debug/terminate` | POST | — | Завершить debug-сессию |
| `/mcp/debug/stack` | GET | — | Стек текущего suspended потока |

### Breakpoints

| Endpoint | Method | Параметры | Описание |
|----------|--------|-----------|----------|
| `/mcp/breakpoints/set` | POST | `{"mode":"start"|"end","session":"last"}` | Расставить breakpoints из трейса |
| `/mcp/breakpoints/clear` | POST | — | Снять все managed breakpoints |
| `/mcp/breakpoints/list` | GET | — | Список managed breakpoints |

### Сессии (SQLite)

| Endpoint | Method | Параметры | Описание |
|----------|--------|-----------|----------|
| `/mcp/sessions/list` | GET | — | Все сессии (id, project, steps, status, timestamps) |
| `/mcp/sessions/last` | GET | — | Последняя сессия |
| `/mcp/sessions/steps` | GET/POST | `session_id` (query param или body) | Полный лог конкретной сессии |

## Формат трейса

Каждый шаг содержит 12 полей:

```json
{
  "procedure": "addOne",
  "line": 29,
  "module": "L/tracer_test_17/src/com/test/TracerTestApp.java",
  "thread_name": "main",
  "thread_id": 1971996155,
  "ts": 1780393616645,
  "char_start": -1,
  "char_end": -1,
  "stack_depth": 2,
  "parent_seq": 10,
  "stack": [{"procedure":"addOne","line":29},{"procedure":"main","line":11}],
  "variables": {"x":{"type":"int","value":"4"}}
}
```

| Поле | Описание |
|------|----------|
| `procedure` | Имя процедуры из `IStackFrame.getName()` |
| `line` | Номер строки |
| `module` | Путь к модулю через `ISourceLocator` |
| `thread_name` | Имя потока (`thread.getName()`) |
| `thread_id` | `identityHashCode` потока |
| `ts` | `System.currentTimeMillis()` |
| `char_start/end` | Позиция символа из `IStackFrame` |
| `stack_depth` | Глубина стека вызовов |
| `parent_seq` | Seq шага-родителя (алгоритм отслеживания depth transitions) |
| `stack` | Полный стек: `[{procedure, line}, ...]` |
| `variables` | Локальные переменные: `{name: {type, value}}` |

## Целевой сценарий

```
1. /mcp/debug/launch         → запуск Java-приложения в debug
2. /mcp/start                → начать запись
3. /mcp/run {"steps":0}      → auto-step (безлимитный, stepInto)
4. /mcp/stop                 → остановить запись
5. /mcp/debug/terminate      → завершить debug-сессию
6. /mcp/sessions/last        → получить ID последней сессии
7. /mcp/breakpoints/set      → расставить breakpoints из трейса
     {"mode":"start","session":"last"}
8. /mcp/debug/launch         → повторный запуск
9. /mcp/debug/status         → {"state":"suspended","location":"main:10"}
10. /mcp/debug/step          → пошаговая отладка
11. /mcp/debug/resume        → до следующего breakpoint
```

## Архитектура

```
┌─ Eclipse Debug Framework ──────────────────────────────────┐
│                                                             │
│  SUSPEND event ──→ TracerListener.handleDebugEvents        │
│                       │                                     │
│                       ├─→ ISourceLocator (module)           │
│                       ├─→ getVariables() (variables)        │
│                       ├─→ getStackFrames() (stack+parentSeq)│
│                       ├─→ queue.offer(entry)  (async)       │
│                       └─→ thread.stepOver/Into()  (direct)  │
│                                                             │
│  ┌─ Writer Thread ────────────┐  ┌─ SQLite Writer ──────┐  │
│  │  queue → entries → JSON    │  │  queue → batch → DB   │  │
│  └────────────────────────────┘  └───────────────────────┘  │
│                                                             │
│  ┌─ TracerBreakpoints ────────┐  ┌─ Debug Control ───────┐  │
│  │  JDIDebugModel (reflective)│  │  launch/step/resume   │  │
│  └────────────────────────────┘  └───────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
  HttpServer (com.sun.net.httpserver, 17 endpoints)
    /mcp/health, /mcp/start, /mcp/run, /mcp/stop, /mcp/status
    /mcp/debug/launch, /mcp/debug/status, /mcp/debug/step
    /mcp/debug/resume, /mcp/debug/terminate, /mcp/debug/stack
    /mcp/breakpoints/set, /mcp/breakpoints/clear, /mcp/breakpoints/list
    /mcp/sessions/list, /mcp/sessions/last, /mcp/sessions/steps
```

## Структура проекта

```
plugin/src/plugin17/test/
├── TestActivator.java          # BundleActivator + HttpServer (17 endpoints)
├── TracerListener.java         # IDebugEventSetListener + debug control + capture
├── TracerStorage.java          # SQLite: sessions, steps, analysis, call_edges
├── TracerBreakpoints.java      # Breakpoint management (reflective JDIDebugModel)
├── StepEntry.java              # record (12 полей)
├── StepEntrySerializationTest.java
├── McpHealthTest.java          # PDE JUnit
└── TracerIntegrationTest.java  # PDE JUnit

scripts/
├── eclipse-launch.sh           # Управление Eclipse 2026-03
├── edt-launch.sh               # Управление EDT 2025.1.5
├── build.sh                    # Сборка jar
├── setup_dev_env.sh            # Настройка окружения (sqlite-jdbc)
└── analyze_trace.py            # Post-processing: call tree, deps, hot spots

docs/
├── AI-CONTEXT.md               # Документация для AI-агентов
├── 01_context.md – 10_lib_checklist.md
└── ...
```

### Зависимости (compile classpath)

```
org.eclipse.osgi_*.jar
org.eclipse.core.runtime_*.jar
org.eclipse.equinox.common_*.jar
org.eclipse.debug.core_*.jar
org.eclipse.core.resources_*.jar
org.eclipse.core.jobs_*.jar
```

Runtime: `org.eclipse.jdt.debug` загружается через `Platform.getBundle()` + reflection (OSGi workaround).

### Сборка

```bash
EP="/opt/eclipse-latest/plugins"
CP="$EP/org.eclipse.osgi_*.jar:$EP/org.eclipse.core.runtime_*.jar:$EP/org.eclipse.equinox.common_*.jar:$EP/org.eclipse.debug.core_*.jar:$EP/org.eclipse.core.resources_*.jar:$EP/org.eclipse.core.jobs_*.jar"
javac --release 17 -d plugin/bin -cp "$CP" plugin/src/plugin17/test/*.java
jar cfm plugin17-test_1.0.0.jar plugin/META-INF/MANIFEST.MF -C plugin/bin .
```

## История версий

| Тег | Описание |
|-----|----------|
| v1.0 | Pipeline tracer (базовая версия) |
| v1.1–v1.5 | Обогащение данных: module, variables, threadName, charPos, callStack+parentSeq |
| v2.0 | Research B: analyze_trace.py (call tree, dependency graph, hot spots) |
| v3.0 | SQLite storage (sessions, steps, analysis, call_edges) |
| v4.0 | Управление: dedup, filters, limits, /mcp/status |
| v5.0 | Auto-step: params, stopOn conditions |
| v6.0 | Performance + Reliability + Server config + Auth |
| v7.0 | Breakpoint Management API |
| v8.0 | Тестирование: stepInto hierarchy + breakpoints set/clear |
| v9.0 | Debug Session Management API (launch/step/resume/terminate/stack) |
| v9.1 | Sessions list + per-session steps API |
| v9.2 | Auto-detect project_name in sessions |

## Лицензия

MIT
