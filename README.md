# EDT Debug Tracer

Eclipse OSGi-плагин для трассировки отладки 1С:EDT. Перехватывает SUSPEND-события Eclipse Debug API, записывает позицию (процедура, строка, поток) и автоматически выполняет stepOver для пошагового прохождения кода.

## Возможности

- **Перехват debug-событий** через `IDebugEventSetListener` — нулевой overhead, 0% потерь
- **Pipeline auto-step** — stepOver вызывается напрямую из event handler, запись делегирована фоновому потоку через `BlockingQueue`
- **Запись в файл** — JSON-массив `StepEntry[]` с процедурой, строкой, thread_id, timestamp
- **REST API** — `/mcp/health`, `/mcp/start`, `/mcp/run`, `/mcp/stop`
- **Per-workspace конфигурация** — каждый Eclipse instance читает свой `{workspace}/.edt-debug-tracer/tracer.properties`

## Быстрый старт

### Установка

```bash
# 1. Собрать jar
git clone https://github.com/lytkinam/edt-debug-tracer
cd edt-debug-tracer
javac --release 17 -d plugin/bin -cp "$EDT_PLUGINS/org.eclipse.osgi_*.jar:$EDT_PLUGINS/org.eclipse.core.runtime_*.jar:$EDT_PLUGINS/org.eclipse.equinox.common_*.jar:$EDT_PLUGINS/org.eclipse.debug.core_*.jar" plugin/src/plugin17/test/*.java

# 2. Упаковать jar
jar cfm plugin/plugin17-test_1.0.0.jar plugin/META-INF/MANIFEST.MF -C plugin/bin .

# 3. Установить в EDT
cp plugin/plugin17-test_1.0.0.jar $EDT_DIR/plugins/

# 4. Добавить в bundles.info
echo "plugin17-test,1.0.0.qualifier,plugins/plugin17-test_1.0.0.jar,4,true" >> $EDT_DIR/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info

# 5. Перезапустить EDT (БЕЗ -clean)
```

### Конфигурация

Создать файл `{workspace}/.edt-debug-tracer/tracer.properties`:

```properties
port=18080
output=~/.edt-debug-tracer/trace.json
```

| Параметр | Описание | По умолчанию |
|----------|----------|-------------|
| `port` | HTTP-порт плагина | 18080 |
| `output` | Путь к файлу трейса | `{workspace}/.edt-debug-tracer/trace.json` |

Для разных Eclipse instance — разные workspace → разные конфиги:

| Instance | Workspace | Порт |
|----------|-----------|------|
| Eclipse 2026-03 (dev) | `/home/ai/workspace-eclipse-latest/` | 18060 |
| EDT (production) | `/home/ai/workspace-edt2025/` | 18080 |

### Использование

```bash
# 1. Поставить breakpoint в EDT (вручную или через codepilot1c MCP)

# 2. Запустить запись
curl -X POST http://localhost:18080/mcp/start

# 3. Запустить debug-сессию в EDT (F11 или через codepilot1c)
#    Трейсер начнёт запись при первом SUSPEND

# 4. Запустить auto-step (опционально — для автоматического прохождения)
curl -X POST http://localhost:18080/mcp/run -d '{"steps":100}'

# 5. Остановить запись и сохранить в файл
curl -X POST http://localhost:18080/mcp/stop
```

## API Reference

### `GET /mcp/health`

Проверка работоспособности.

```json
{"ok":true, "recording":false, "autoStepping":false, "entries":0, "port":18080}
```

### `POST /mcp/start`

Начать запись трейса. Очищает буфер, запускает writer-thread.

```json
{"started":true}
```

### `POST /mcp/run`

Запустить auto-step. После каждого SUSPEND автоматически вызывает `thread.stepOver()`.

Request: `{"steps": 100}`

```json
{"autoStep":true, "maxSteps":100}
```

### `POST /mcp/stop`

Остановить запись. Сохраняет трейс в файл (из конфига `output`).

```json
{"stopped":true, "count":30, "totalSteps":30, "file":"/path/to/trace.json"}
```

## Формат трейса

JSON-массив `StepEntry[]`:

```json
[
  {"procedure":"МодульСеанса.УстановкаПараметровСеанса(ИменаПараметровСеанса = Массив) строка: 16","line":16,"module":"","thread_id":646430608,"ts":1780382800451},
  {"procedure":"ОбщийМодуль.СтандартныеПодсистемыСервер.Модуль.УстановкаПараметровСеанса(ИменаПараметровСеанса = ) строка: 45","line":45,"module":"","thread_id":646430608,"ts":1780382800738}
]
```

| Поле | Тип | Описание |
|------|-----|----------|
| `procedure` | string | Полное имя процедуры с параметрами из `IStackFrame.getName()` |
| `line` | int | Номер строки из `IStackFrame.getLineNumber()` |
| `module` | string | Модуль (пока пустой, зарезервирован для `ISourceLocator`) |
| `thread_id` | int | `System.identityHashCode(thread)` |
| `ts` | long | `System.currentTimeMillis()` |

## Архитектура

```
┌─ Eclipse Debug Framework ──────────────────────────────┐
│                                                         │
│  SUSPEND event ──→ IDebugEventSetListener              │
│                     (TracerListener.handleDebugEvents) │
│                       │                                 │
│                       ├─→ read IStackFrame (fast, ~1μs)│
│                       ├─→ queue.offer(entry)  (~0.5μs) │
│                       └─→ thread.stepOver()   (~10μs)  │
│                                                         │
│  ┌─ Writer Thread (daemon) ───────────────────────┐    │
│  │  queue.take() → entries.add() → fileWriter     │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
         │
         ▼
  HttpServer (com.sun.net.httpserver)
    GET  /mcp/health
    POST /mcp/start
    POST /mcp/run     ← auto-step controller
    POST /mcp/stop    ← flush to file
```

### Pipeline (hot path)

```
SUSPEND event
  → frame.getName() + getLineNumber()    ~1μs
  → queue.offer(entry)                    ~0.5μs
  → thread.stepOver()                     ~10μs (direct, no Thread creation)
  ────────────────────────────────────────
  Total hot path:                         ~12μs
```

Writer-thread обрабатывает очередь асинхронно, не блокируя debug event dispatch.

## Производительность

### Бенчмарки

| Метод | Steps/sec | ms/step | Потери |
|-------|-----------|---------|--------|
| codepilot1c MCP (step + wait + get_variables) | ~6 | ~160 | возможны |
| edt-debug-tracer (event-driven, Thread per step) | 48 | 20 | 0% |
| **edt-debug-tracer (pipeline, direct stepOver)** | **192** | **5.2** | **0%** |

### На реальном BSL-коде (EDT)

| Метрика | Java test app | BSL 1С (EDT) |
|---------|--------------|---------------|
| Steps/sec | 192 | 7.0 |
| ms/step | 5.2 | 142 |
| Потери | 0% | 0% |

BSL debug engine EDT значительно тяжелее Java JDI (~140ms vs ~5ms на step), но потерь нет в обоих случаях.

## Разработка

### Структура проекта

```
plugin/
├── META-INF/MANIFEST.MF
├── src/plugin17/test/
│   ├── TestActivator.java      # BundleActivator + HttpServer
│   ├── TracerListener.java     # IDebugEventSetListener + pipeline
│   ├── StepEntry.java          # record(procedure, line, module, threadId, ts)
│   ├── McpHealthTest.java      # PDE JUnit test
│   └── TracerIntegrationTest.java
├── bin/                        # compiled classes
└── lib/                        # (reserved for sqlite-jdbc)
```

### Зависимости (compile classpath)

```
org.eclipse.osgi_*.jar
org.eclipse.core.runtime_*.jar
org.eclipse.equinox.common_*.jar
org.eclipse.debug.core_*.jar
org.junit_4*.jar (for tests)
```

### Сборка

```bash
JAVAC="/opt/java/jdk-21/bin/javac"   # или любой JDK 17+
EP="/opt/eclipse-latest/plugins"      # или EDT plugins

CP="$EP/org.eclipse.osgi_*.jar:$EP/org.eclipse.core.runtime_*.jar:$EP/org.eclipse.equinox.common_*.jar:$EP/org.eclipse.debug.core_*.jar"

javac --release 17 -d plugin/bin -cp "$CP" plugin/src/plugin17/test/*.java
jar cfm plugin/plugin17-test_1.0.0.jar plugin/META-INF/MANIFEST.MF -C plugin/bin .
```

### Тестирование через Eclipse PDE MCP

```bash
# Через AssistAI MCP (eclipse-pde endpoint)
curl -X POST http://localhost:8124/mcp/eclipse-pde \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"runJUnitPluginTestClass",
                 "arguments":{"projectName":"plugin17-test",
                              "className":"plugin17.test.McpHealthTest"}}}'
```

### Доработка

**Добавить поле в StepEntry:**
1. Добавить поле в `StepEntry.java` (record)
2. Заполнить в `TracerListener.handleDebugEvents()` из `IStackFrame`
3. Пересобрать jar, переустановить

**Добавить endpoint:**
1. Добавить `server.createContext()` в `TestActivator.start()`
2. Пересобрать jar, переустановить

**Заполнить `module`:**
```java
ILaunch launch = thread.getLaunch();
ISourceLocator locator = launch.getSourceLocator();
if (locator != null) {
    Object src = locator.getSourceElement(frame);
    module = src != null ? src.toString() : "";
}
```

## Лицензия

MIT
