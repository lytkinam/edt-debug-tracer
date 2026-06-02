# EDT Debug Tracer — Next Steps

Все значения параметров — в конфигурационном файле `{workspace}/.edt-debug-tracer/tracer.properties`. Зашивать значения в код — антипаттерн.

---

## Приоритет 1: Обогащение данных

### 1.1 Заполнение поля `module`

Сейчас `module` пустой. Заполнить через `ISourceLocator`:

```java
ILaunch launch = thread.getLaunch();
ISourceLocator locator = launch.getSourceLocator();
if (locator != null) {
    Object src = locator.getSourceElement(frame);
    module = src != null ? src.toString() : "";
}
```

**Конфигурация:**
```properties
capture.module.enabled=true
capture.module.fallback=unknown
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `capture.module.enabled` | boolean | `true` | Включить захват module через ISourceLocator |
| `capture.module.fallback` | string | `""` | Значение если ISourceLocator недоступен |

### 1.2 Захват переменных стека

Добавить в StepEntry список переменных текущего фрейма:

```java
IVariable[] vars = frame.getVariables();
// → Map<String, String> variables (name → value)
```

**Конфигурация:**
```properties
capture.variables.enabled=false
capture.variables.maxDepth=1
capture.variables.maxCount=50
capture.variables.includeTypes=true
capture.variables.maxValueLength=200
capture.variables.excludeNames=__internal*,_tmp*
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `capture.variables.enabled` | boolean | `false` | Включить захват переменных (влияет на производительность) |
| `capture.variables.maxDepth` | int | `1` | Глубина рекурсии для вложенных объектов |
| `capture.variables.maxCount` | int | `50` | Максимум переменных на фрейм |
| `capture.variables.includeTypes` | boolean | `true` | Захватывать тип переменной (`getReferenceTypeName()`) |
| `capture.variables.maxValueLength` | int | `200` | Обрезать значения длиннее N символов |
| `capture.variables.excludeNames` | string | `""` | Паттерны имён для исключения (через запятую, glob) |

### 1.3 Thread name вместо identityHashCode

Заменить `System.identityHashCode(thread)` на `thread.getName()`:

```java
String threadName = thread.getName(); // "Сервер [admin]", "Клиент"
```

**Конфигурация:**
```properties
capture.thread.useName=true
capture.thread.includeId=true
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `capture.thread.useName` | boolean | `true` | Использовать `thread.getName()` |
| `capture.thread.includeId` | boolean | `true` | Сохранять также identityHashCode для уникальности |

### 1.4 Захват charStart/charEnd

Позиция символа в исходнике (для точного позиционирования в IDE):

```java
int charStart = frame.getCharStart();
int charEnd = frame.getCharEnd();
```

**Конфигурация:**
```properties
capture.charPosition.enabled=true
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `capture.charPosition.enabled` | boolean | `true` | Захватывать charStart/charEnd |

### 1.5 Захват call stack (полный стек фреймов)

Вместо только top frame — весь стек вызовов:

```java
IStackFrame[] frames = thread.getStackFrames();
// → List<StackFrameInfo> callStack
```

**Конфигурация:**
```properties
capture.callStack.enabled=false
capture.callStack.maxDepth=20
capture.callStack.includeVariables=false
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `capture.callStack.enabled` | boolean | `false` | Захватывать весь стек (сильно влияет на производительность) |
| `capture.callStack.maxDepth` | int | `20` | Максимальная глубина стека |
| `capture.callStack.includeVariables` | boolean | `false` | Захватывать переменные каждого фрейма |

---

## Приоритет 2: Хранение данных

### 2.1 MySQL: запись сырых данных

Запись каждого StepEntry в MySQL в реальном времени. Отдельный процесс/скрипт разбирает сырые данные после остановки записи.

**Схема таблиц:**

```sql
CREATE TABLE tracer_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) UNIQUE NOT NULL,
    workspace VARCHAR(512),
    started_at DATETIME(3) NOT NULL,
    stopped_at DATETIME(3),
    status ENUM('active','stopped','error') DEFAULT 'active',
    total_steps INT DEFAULT 0,
    config_json TEXT
);

CREATE TABLE tracer_steps (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    seq INT NOT NULL,
    ts BIGINT NOT NULL,
    thread_id INT,
    thread_name VARCHAR(256),
    procedure_name VARCHAR(1024),
    line_number INT,
    module VARCHAR(512),
    char_start INT,
    char_end INT,
    variables_json TEXT,
    call_stack_json TEXT,
    INDEX idx_session_seq (session_id, seq),
    INDEX idx_session_ts (session_id, ts),
    INDEX idx_procedure (procedure_name(100))
);

CREATE TABLE tracer_analysis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    analysis_type VARCHAR(64) NOT NULL,
    result_json LONGTEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_session_type (session_id, analysis_type)
);
```

**Конфигурация:**
```properties
storage.type=file
storage.mysql.host=localhost
storage.mysql.port=3306
storage.mysql.database=tracer
storage.mysql.username=tracer
storage.mysql.password=
storage.mysql.table.sessions=tracer_sessions
storage.mysql.table.steps=tracer_steps
storage.mysql.table.analysis=tracer_analysis
storage.mysql.batch.size=100
storage.mysql.batch.timeout.ms=500
storage.mysql.pool.maxSize=5
storage.mysql.ssl=false
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `storage.type` | string | `file` | Тип хранилища: `file`, `mysql`, `sqlite`, `both` |
| `storage.mysql.host` | string | `localhost` | Хост MySQL |
| `storage.mysql.port` | int | `3306` | Порт MySQL |
| `storage.mysql.database` | string | `tracer` | Имя базы данных |
| `storage.mysql.username` | string | `tracer` | Пользователь |
| `storage.mysql.password` | string | `""` | Пароль |
| `storage.mysql.table.sessions` | string | `tracer_sessions` | Таблица сессий |
| `storage.mysql.table.steps` | string | `tracer_steps` | Таблица шагов |
| `storage.mysql.table.analysis` | string | `tracer_analysis` | Таблица результатов анализа |
| `storage.mysql.batch.size` | int | `100` | Размер batch для INSERT |
| `storage.mysql.batch.timeout.ms` | int | `500` | Flush batch по таймауту (ms) |
| `storage.mysql.pool.maxSize` | int | `5` | Максимум соединений в пуле |
| `storage.mysql.ssl` | boolean | `false` | SSL-подключение |

### 2.2 SQLite: локальная альтернатива

Для случаев когда MySQL недоступен (offline, dev-окружение):

```properties
storage.type=sqlite
storage.sqlite.path={workspace}/.edt-debug-tracer/trace.db
storage.sqlite.wal=true
storage.sqlite.batch.size=50
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `storage.type` | string | `file` | `sqlite` для локальной БД |
| `storage.sqlite.path` | string | `{workspace}/.edt-debug-tracer/trace.db` | Путь к файлу БД |
| `storage.sqlite.wal` | boolean | `true` | WAL mode для concurrent reads |
| `storage.sqlite.batch.size` | int | `50` | Размер batch |

### 2.3 Файл: текущий режим

```properties
storage.type=file
storage.file.path={workspace}/.edt-debug-tracer/trace.json
storage.file.format=json
storage.file.pretty=false
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `storage.file.path` | string | `{workspace}/.edt-debug-tracer/trace.json` | Путь к файлу |
| `storage.file.format` | string | `json` | Формат: `json` (массив) или `ndjson` (по строке) |
| `storage.file.pretty` | boolean | `false` | Форматированный JSON (для чтения человеком) |

### 2.4 Dual storage (file + mysql)

Запись одновременно в файл и в MySQL — для надёжности:

```properties
storage.type=both
storage.file.path={workspace}/.edt-debug-tracer/trace.json
storage.mysql.host=localhost
storage.mysql.database=tracer
```

### 2.5 Пост-обработка сырых данных

Отдельный скрипт/сервис, который читает сырые шаги и выполняет анализ:

**Виды анализа:**
- **Loop collapse** — свёртка повторяющихся паттернов (STEP/REPEAT/LOOP)
- **Call tree** — построение дерева вызовов из flat-трейс
- **Hot spots** — процедуры с наибольшим количеством шагов
- **Thread transitions** — переходы между потоками
- **Timing analysis** — время выполнения каждой процедуры
- **Diff** — сравнение двух сессий

**Конфигурация пост-обработки:**
```properties
analysis.enabled=true
analysis.types=loopCollapse,callTree,hotSpots,timing
analysis.loopCollapse.minRepeat=2
analysis.loopCollapse.maxPattern=20
analysis.hotSpots.topN=20
analysis.output.format=json
analysis.output.path={workspace}/.edt-debug-tracer/analysis.json
analysis.autoRun=false
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `analysis.enabled` | boolean | `true` | Включить пост-обработку |
| `analysis.types` | string | `loopCollapse,callTree,hotSpots,timing` | Виды анализа (через запятую) |
| `analysis.loopCollapse.minRepeat` | int | `2` | Минимум повторений для свёртки |
| `analysis.loopCollapse.maxPattern` | int | `20` | Максимальная длина паттерна |
| `analysis.hotSpots.topN` | int | `20` | Количество top-процедур |
| `analysis.output.format` | string | `json` | Формат вывода: `json`, `html`, `csv` |
| `analysis.output.path` | string | `{workspace}/.edt-debug-tracer/analysis.json` | Путь к файлу анализа |
| `analysis.autoRun` | boolean | `false` | Автоматически запускать при `/mcp/stop` |

---

## Приоритет 3: Управление и контроль

### 3.1 Дедупликация записей

Убрать дубликаты (два одинаковых SUSPEND на одной строке).

**Конфигурация:**
```properties
filter.dedup.enabled=true
filter.dedup.window.ms=50
filter.dedup.matchFields=procedure,line,threadId
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `filter.dedup.enabled` | boolean | `true` | Включить дедупликацию |
| `filter.dedup.window.ms` | int | `50` | Окно дедупликации (ms) |
| `filter.dedup.matchFields` | string | `procedure,line,threadId` | Поля для сравнения (через запятую) |

### 3.2 Фильтрация по модулям/процедурам

```properties
filter.include.modules=
filter.exclude.modules=
filter.include.procedures=
filter.exclude.procedures=
filter.include.lines=
filter.exclude.lines=
filter.mode=whitelist
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `filter.include.modules` | string | `""` | Включать только эти модули (glob, через запятую) |
| `filter.exclude.modules` | string | `""` | Исключать эти модули |
| `filter.include.procedures` | string | `""` | Включать только эти процедуры |
| `filter.exclude.procedures` | string | `""` | Исключать эти процедуры |
| `filter.include.lines` | string | `""` | Включать только эти диапазоны строк (`10-50,100-200`) |
| `filter.exclude.lines` | string | `""` | Исключать эти диапазоны |
| `filter.mode` | string | `whitelist` | `whitelist` (include имеет приоритет) или `blacklist` (exclude имеет приоритет) |

### 3.3 Лимит записей

```properties
limit.maxEntries=0
limit.maxDuration.seconds=0
limit.action=stop
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `limit.maxEntries` | int | `0` | Максимум записей (0 = без лимита) |
| `limit.maxDuration.seconds` | int | `0` | Максимальная длительность записи (сек, 0 = без лимита) |
| `limit.action` | string | `stop` | Действие при достижении: `stop`, `flush`, `rollover` |

### 3.4 Endpoint `/mcp/status`

```properties
status.includeConfig=false
status.includeLastEntry=true
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `status.includeConfig` | boolean | `false` | Включить текущий конфиг в ответ |
| `status.includeLastEntry` | boolean | `true` | Включить последнюю записанную запись |

---

## Приоритет 4: Auto-step

### 4.1 Параметры auto-step

```properties
autoStep.defaultSteps=1000
autoStep.maxSteps=100000
autoStep.delay.ms=0
autoStep.stepType=over
autoStep.stopOnTerminate=true
autoStep.stopOnException=false
autoStep.stopOnModule=
autoStep.stopOnProcedure=
autoStep.stopOnLine=0
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `autoStep.defaultSteps` | int | `1000` | Количество шагов по умолчанию (если не указано в `/mcp/run`) |
| `autoStep.maxSteps` | int | `100000` | Абсолютный максимум шагов |
| `autoStep.delay.ms` | int | `0` | Задержка между шагами (ms, для замедления) |
| `autoStep.stepType` | string | `over` | Тип шага: `over`, `into`, `return` |
| `autoStep.stopOnTerminate` | boolean | `true` | Остановить при TERMINATE event |
| `autoStep.stopOnException` | boolean | `false` | Остановить при исключении в отлаживаемом коде |
| `autoStep.stopOnModule` | string | `""` | Остановить при входе в модуль |
| `autoStep.stopOnProcedure` | string | `""` | Остановить при входе в процедуру |
| `autoStep.stopOnLine` | int | `0` | Остановить на строке (0 = отключено) |

### 4.2 Conditional auto-step

Условия для выполнения stepOver (шаг только если условие истинно):

```properties
autoStep.condition.enabled=false
autoStep.condition.expression=
autoStep.condition.language=simple
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `autoStep.condition.enabled` | boolean | `false` | Включить условный step |
| `autoStep.condition.expression` | string | `""` | Выражение: `module.contains("Стандартные")`, `line > 100` |
| `autoStep.condition.language` | string | `simple` | Язык выражений: `simple` (встроенный), `javascript` (GraalVM) |

---

## Приоритет 5: Интеграция

### 5.1 Интеграция с codepilot1c MCP

```properties
integration.codepilot.enabled=false
integration.codepilot.url=http://localhost:8765/mcp
integration.codepilot.token=
integration.codepilot.syncBreakpoints=false
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `integration.codepilot.enabled` | boolean | `false` | Включить интеграцию |
| `integration.codepilot.url` | string | `http://localhost:8765/mcp` | URL codepilot1c MCP |
| `integration.codepilot.token` | string | `""` | Bearer token |
| `integration.codepilot.syncBreakpoints` | boolean | `false` | Синхронизировать breakpoints |

### 5.2 NDJSON формат

```properties
output.format=json
output.ndjson.flushEveryEntry=true
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `output.format` | string | `json` | `json` (массив) или `ndjson` (по строке) |
| `output.ndjson.flushEveryEntry` | boolean | `true` | Flush после каждой записи (для NDJSON) |

### 5.3 WebSocket endpoint

```properties
websocket.enabled=false
websocket.path=/mcp/ws
websocket.events=recording_started,step_captured,recording_stopped,auto_step_complete
websocket.maxClients=5
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `websocket.enabled` | boolean | `false` | Включить WebSocket |
| `websocket.path` | string | `/mcp/ws` | Путь WebSocket endpoint |
| `websocket.events` | string | все события | События для отправки (через запятую) |
| `websocket.maxClients` | int | `5` | Максимум одновременных подключений |

### 5.4 HTTP callback (webhook)

Уведомления на внешний URL:

```properties
webhook.enabled=false
webhook.url=
webhook.events=recording_stopped,auto_step_complete
webhook.method=POST
webhook.headers=
webhook.timeout.ms=5000
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `webhook.enabled` | boolean | `false` | Включить webhook |
| `webhook.url` | string | `""` | URL для уведомлений |
| `webhook.events` | string | `recording_stopped` | События (через запятую) |
| `webhook.method` | string | `POST` | HTTP метод |
| `webhook.headers` | string | `""` | Дополнительные заголовки (`Key:Value,Key:Value`) |
| `webhook.timeout.ms` | int | `5000` | Таймаут (ms) |

---

## Приоритет 6: Производительность

### 6.1 Асинхронная запись на диск

```properties
writer.buffer.size=100000
writer.flush.interval.entries=100
writer.flush.interval.ms=500
writer.thread.priority=MIN
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `writer.buffer.size` | int | `100000` | Размер очереди BlockingQueue |
| `writer.flush.interval.entries` | int | `100` | Flush каждые N записей |
| `writer.flush.interval.ms` | int | `500` | Flush по таймауту (ms) |
| `writer.thread.priority` | string | `MIN` | Приоритет writer-thread: `MIN`, `NORM`, `MAX` |

### 6.2 Batch stepOver

```properties
autoStep.batch.enabled=false
autoStep.batch.size=5
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `autoStep.batch.enabled` | boolean | `false` | Включить batch stepOver |
| `autoStep.batch.size` | int | `5` | Количество stepOver в пакете |

### 6.3 Профилирование hot path

```properties
profiling.enabled=false
profiling.logPath={workspace}/.edt-debug-tracer/profiling.log
profiling.sampleRate=100
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `profiling.enabled` | boolean | `false` | Включить профилирование hot path |
| `profiling.logPath` | string | `{workspace}/.edt-debug-tracer/profiling.log` | Путь к файлу профилирования |
| `profiling.sampleRate` | int | `100` | Логировать каждый N-й шаг (1 = каждый) |

---

## Приоритет 7: Надёжность

### 7.1 Обработка завершения debug-сессии

```properties
reliability.stopOnTerminate=true
reliability.flushOnTerminate=true
reliability.resumeOnTerminate=false
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `reliability.stopOnTerminate` | boolean | `true` | Остановить запись при TERMINATE |
| `reliability.flushOnTerminate` | boolean | `true` | Flush буфера при TERMINATE |
| `reliability.resumeOnTerminate` | boolean | `false` | Auto-resume потока при TERMINATE |

### 7.2 Восстановление после ошибок

```properties
reliability.maxErrors=10
reliability.errorAction=log
reliability.retryStepOver=false
reliability.retryDelay.ms=100
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `reliability.maxErrors` | int | `10` | Максимум ошибок до остановки |
| `reliability.errorAction` | string | `log` | Действие: `log`, `stop`, `skip` |
| `reliability.retryStepOver` | boolean | `false` | Повторять stepOver при ошибке |
| `reliability.retryDelay.ms` | int | `100` | Задержка перед retry (ms) |

### 7.3 Graceful shutdown

```properties
reliability.gracefulShutdown=true
reliability.shutdownTimeout.ms=5000
reliability.saveMetadata=true
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `reliability.gracefulShutdown` | boolean | `true` | Flush + close при bundle stop |
| `reliability.shutdownTimeout.ms` | int | `5000` | Таймаут ожидания flush (ms) |
| `reliability.saveMetadata` | boolean | `true` | Сохранить метаданные сессии |

---

## Приоритет 8: UI и отладка самого трейсера

### 8.1 Eclipse View

```properties
ui.view.enabled=false
ui.view.showLastEntries=10
ui.view.autoRefresh=true
ui.view.refreshInterval.ms=1000
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `ui.view.enabled` | boolean | `false` | Включить Eclipse View |
| `ui.view.showLastEntries` | int | `10` | Количество последних записей в View |
| `ui.view.autoRefresh` | boolean | `true` | Автообновление |
| `ui.view.refreshInterval.ms` | int | `1000` | Интервал обновления (ms) |

### 8.2 Preferences page

```properties
ui.preferences.enabled=false
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `ui.preferences.enabled` | boolean | `false` | Включить Preferences page |

### 8.3 Логирование событий

```properties
logging.level=INFO
logging.file={workspace}/.metadata/.log
logging.prefix=[tracer]
logging.includeTimestamp=true
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `logging.level` | string | `INFO` | Уровень: `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `logging.file` | string | `{workspace}/.metadata/.log` | Файл лога |
| `logging.prefix` | string | `[tracer]` | Префикс сообщений |
| `logging.includeTimestamp` | boolean | `true` | Включить timestamp |

---

## Приоритет 9: Серверная конфигурация

### 9.1 HTTP Server

```properties
server.port=18080
server.host=localhost
server.backlog=50
server.threads=4
server.cors.enabled=false
server.cors.origins=*
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `server.port` | int | `18080` | HTTP порт |
| `server.host` | string | `localhost` | Хост (`0.0.0.0` для всех интерфейсов) |
| `server.backlog` | int | `50` | Backlog для ServerSocket |
| `server.threads` | int | `4` | Количество worker threads |
| `server.cors.enabled` | boolean | `false` | Включить CORS |
| `server.cors.origins` | string | `*` | Разрешённые origins |

### 9.2 Аутентификация

```properties
server.auth.enabled=false
server.auth.token=
server.auth.header=Authorization
server.auth.scheme=Bearer
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `server.auth.enabled` | boolean | `false` | Включить аутентификацию |
| `server.auth.token` | string | `""` | Ожидаемый токен |
| `server.auth.header` | string | `Authorization` | Имя заголовка |
| `server.auth.scheme` | string | `Bearer` | Схема аутентификации |

---

## Идеи для исследования

### A. Сравнение с 1C:Enterprise debug protocol

1С имеет собственный debug protocol (HTTP, порт debug-сервера). Сравнить:
- Скорость step-by-step через JDI vs 1C debug protocol
- Полнота данных (переменные, типы, значения)
- Возможность remote debugging

**Конфигурация:**
```properties
research.1cProtocol.enabled=false
research.1cProtocol.host=localhost
research.1cProtocol.port=1550
research.1cProtocol.compareMode=side-by-side
```

### B. Call tree reconstruction

Из flat-треса восстановить дерево вызовов.

**Конфигурация:**
```properties
analysis.callTree.enabled=true
analysis.callTree.detectRecursion=true
analysis.callTree.maxDepth=50
analysis.callTree.groupLoops=true
```

### C. Diff-трейс

Сравнение двух трейсов (до/после изменения кода).

**Конфигурация:**
```properties
analysis.diff.enabled=false
analysis.diff.baselineSession=
analysis.diff.metrics=steps,time,procedures
analysis.diff.outputFormat=html
```

### D. Real-time аналитика

Стриминговая аналитика во время записи (не пост-обработка):

**Конфигурация:**
```properties
realtime.enabled=false
realtime.metrics=topProcedures,stepRate,threadDistribution
realtime.window.seconds=60
realtime.pushInterval.ms=2000
```

### E. Мульти-сессия

Одновременная запись нескольких debug-сессий:

**Конфигурация:**
```properties
multiSession.enabled=false
multiSession.maxSessions=5
multiSession.isolation=separate
multiSession.output.dir={workspace}/.edt-debug-tracer/sessions/
```

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|-------------|----------|
| `multiSession.enabled` | boolean | `false` | Разрешить несколько одновременных сессий |
| `multiSession.maxSessions` | int | `5` | Максимум сессий |
| `multiSession.isolation` | string | `separate` | `separate` (отдельные файлы) или `merged` (один файл) |
| `multiSession.output.dir` | string | `sessions/` | Директория для файлов сессий |

---

## Сводная таблица всех параметров

Полный список параметров в `tracer.properties` (группировка по разделам):

| Раздел | Префикс | Параметров | Описание |
|--------|---------|-----------|----------|
| Server | `server.*` | 8 | HTTP server, auth, CORS |
| Capture | `capture.*` | 10 | Данные для захвата (module, variables, thread, callStack) |
| Storage | `storage.*` | 17 | MySQL, SQLite, file, dual storage |
| Filter | `filter.*` | 10 | Дедупликация, include/exclude |
| Limit | `limit.*` | 3 | Лимиты записей и длительности |
| AutoStep | `autoStep.*` | 14 | Параметры auto-step, условия, batch |
| Analysis | `analysis.*` | 13 | Пост-обработка, loop collapse, call tree, hot spots |
| Writer | `writer.*` | 4 | Buffer, flush, thread priority |
| Reliability | `reliability.*` | 9 | Error handling, shutdown, retry |
| Integration | `integration.*` | 4 | codepilot1c, webhook |
| Output | `output.*` | 3 | NDJSON, format |
| WebSocket | `websocket.*` | 4 | Real-time уведомления |
| UI | `ui.*` | 6 | Eclipse View, Preferences |
| Logging | `logging.*` | 4 | Уровень, файл, префикс |
| Profiling | `profiling.*` | 3 | Hot path profiling |
| Research | `research.*` | 5 | Экспериментальные фичи |
| MultiSession | `multiSession.*` | 4 | Мульти-сессия |
| **Итого** | | **~105** | |
