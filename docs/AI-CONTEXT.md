# AI Agent Context — EDT Debug Tracer Development

Этот документ описывает особенности работы AI-агента с проектом edt-debug-tracer. Предназначен для восстановления контекста после очистки сессии.

## Точка входа

```
Репозиторий: https://github.com/lytkinam/edt-debug-tracer
Локальный путь: /home/ai/workspace-edt2025/smallbase_test_db/tests/qa/debug_logger/edt-debug-tracer
Текущая версия: v9.2 (tag v9.2-project-name)
Архивная версия: tag v1.0 (commit 14a813a)
Следующие шаги: NEXT-STEPS.md
```

**Начать с:**
1. `cat README.md` — что делает плагин
2. `cat NEXT-STEPS.md` — что делать дальше
3. `cat docs/AI-CONTEXT.md` (этот файл) — как работать

---

## Окружение

### Два Eclipse instance

| Instance | Путь | Workspace | Порт трейсера | JDK | Назначение |
|----------|------|-----------|---------------|-----|------------|
| Eclipse 2026-03 | `/opt/eclipse-latest/` | `/home/ai/workspace-eclipse-latest/` | 18060 | Temurin JDK 21 | Dev/test |
| EDT 2025.1.5 | `/opt/1C/1CE/components/1c-edt-2025.1.5+34-x86_64/` | `/home/ai/workspace-edt2025/` | 18080 | Axiom JDK 17 | Production |

### AssistAI MCP (порт 8124)

Eclipse 2026-03 имеет AssistAI MCP плагин на порту 8124. Доступ через Streamable HTTP (требует `initialize` + session ID):

```bash
TOKEN="0c63003c-aed5-4601-93c7-d1891c562a58"
BASE="http://localhost:8124/mcp"

# Инициализация сессии
SESSION=$(curl -s -D- -X POST "$BASE/eclipse-ide" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"agent","version":"1.0"}}}' \
  | grep -i "mcp-session-id" | tr -d '\r' | awk '{print $2}')
```

**Endpoints:**
- `eclipse-ide` — анализ кода, компиляция, навигация
- `eclipse-coder` — создание/редактирование файлов
- `eclipse-runner` — запуск/отладка Java-приложений
- `eclipse-pde` — PDE JUnit тесты
- `eclipse-context` — кэш ресурсов
- `eclipse-git` — git операции

### codepilot1c MCP (порт 8765)

EDT имеет codepilot1c MCP на порту 8765 (86 tools). Для отладки 1С:
- `debug_status`, `step`, `resume`, `suspend`
- `get_variables`, `get_call_stack`
- `wait_for_break` (event-driven)
- `list_sessions`

---

## Разработка

### Где писать код

Исходники в git repo: `plugin/src/plugin17/test/`

Но Eclipse их **не видит** пока не скопировать в workspace:
```bash
# Git repo → Eclipse workspace
cp /path/to/git/plugin/src/plugin17/test/*.java \
   /home/ai/workspace-eclipse-latest/plugin17-test/src/plugin17/test/
```

**Альтернатива:** использовать `eclipse-coder` MCP для создания файлов напрямую в workspace (Eclipse подхватывает автоматически).

### Компиляция

```bash
JAVAC="/opt/java/jdk-21/bin/javac"
EP="/opt/eclipse-latest/plugins"

CP="$EP/org.eclipse.osgi_3.24.100.v20251215-1416.jar"
CP="$CP:$EP/org.eclipse.core.runtime_3.34.200.v20251220-0953.jar"
CP="$CP:$EP/org.eclipse.equinox.common_3.20.300.v20251111-0312.jar"
CP="$CP:$EP/org.eclipse.debug.core_3.23.200.v20251107-0507.jar"
CP="$CP:$EP/org.eclipse.core.resources_3.23.200.v20251217-0810.jar"
CP="$CP:$EP/org.eclipse.core.jobs_3.15.700.v20250725-1147.jar"

javac --release 17 -d plugin/bin -cp "$CP" plugin/src/plugin17/test/*.java
```

**Важно:** `--release 17` даже при компиляции JDK 21 (для совместимости с EDT).

### Упаковка jar

```bash
jar cfm plugin17-test_1.0.0.jar \
    plugin/META-INF/MANIFEST.MF \
    -C plugin/bin .
```

---

## Тестирование

### PDE JUnit тесты (через AssistAI MCP)

```bash
curl -X POST "$BASE/eclipse-pde" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: $PDE_SESSION" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"runJUnitPluginTestClass",
                 "arguments":{
                   "projectName":"plugin17-test",
                   "className":"plugin17.test.McpHealthTest",
                   "timeout":"60"}}}'
```

**Особенность:** PDE запускает **второй** Eclipse instance с плагином. Тест работает в изолированном OSGi-окружении.

### Ручное тестирование

1. Собрать jar
2. Установить в Eclipse 2026-03 (см. Деплой)
3. Перезапустить Eclipse
4. Проверить: `curl http://localhost:18060/mcp/health`

### Тестирование на EDT

1. Собрать jar (тот же jar, `--release 17`)
2. Установить в EDT (см. Деплой)
3. Перезапустить EDT
4. Поставить breakpoint в 1С
5. `curl -X POST http://localhost:18080/mcp/start`
6. Запустить debug-сессию 1С
7. `curl -X POST http://localhost:18080/mcp/run -d '{"steps":30}'`
8. `curl -X POST http://localhost:18080/mcp/stop`
9. Проверить `~/.edt-debug-tracer/trace.json`

---

## Скрипты запуска

Все скрипты находятся в `scripts/` директории проекта. Каждый скрипт поддерживает команды: `start`, `stop`, `restart`, `status`, `log`.

### Eclipse 2026-03 (dev/test) — `scripts/eclipse-launch.sh`

```bash
# Запустить Eclipse на DISPLAY :1 с workspace-eclipse-latest
./scripts/eclipse-launch.sh start

# Остановить
./scripts/eclipse-launch.sh stop

# Перезапустить
./scripts/eclipse-launch.sh restart

# Проверить статус (процесс, порты 18060/8124)
./scripts/eclipse-launch.sh status

# Показать последние логи
./scripts/eclipse-launch.sh log
```

**Переменные окружения:**

| Переменная | По умолчанию | Описание |
|------------|-------------|----------|
| `ECLIPSE_DIR` | `/opt/eclipse-latest` | Путь к установке Eclipse |
| `ECLIPSE_WORKSPACE` | `/home/ai/workspace-eclipse-latest` | Workspace |
| `ECLIPSE_DISPLAY` | `:1` | X Display (VNC) |

**Порты:** tracer на 18060, AssistAI MCP на 8124.

### EDT 2025.1.5 (production) — `scripts/edt-launch.sh`

```bash
# Запустить EDT на DISPLAY :1 с workspace-edt2025
./scripts/edt-launch.sh start

# Остановить (graceful → force kill)
./scripts/edt-launch.sh stop

# Перезапустить
./scripts/edt-launch.sh restart

# Проверить статус (процесс, порты 8765/18080, lock file)
./scripts/edt-launch.sh status

# Показать последние логи и ошибки
./scripts/edt-launch.sh log
```

**Переменные окружения:**

| Переменная | По умолчанию | Описание |
|------------|-------------|----------|
| `EDT_DIR` | `/opt/1C/1CE/components/1c-edt-2025.1.5+34-x86_64` | Путь к EDT |
| `EDT_WORKSPACE` | `/home/ai/workspace-edt2025` | Workspace |
| `EDT_DISPLAY` | `:1` | X Display (VNC) |

**Порты:** codepilot1c MCP на 8765, tracer на 18080.

**ВАЖНО:** Никогда не перезапускать EDT с флагом `-clean` — ломается secure storage.

### Сборка — `scripts/build.sh`

```bash
# Собрать jar (javac + jar)
./scripts/build.sh

# Собрать и установить в Eclipse
./scripts/build.sh --install

# Очистить артефакты
./scripts/build.sh --clean
```

### Настройка окружения — `scripts/setup_dev_env.sh`

```bash
# Скачать sqlite-jdbc, настроить classpath
./scripts/setup_dev_env.sh
```

### Быстрый чек всего окружения

```bash
# Статус обоих Eclipse
./scripts/eclipse-launch.sh status
./scripts/edt-launch.sh status

# Или одной командой:
echo "=== Eclipse ===" && ./scripts/eclipse-launch.sh status
echo "=== EDT ===" && ./scripts/edt-launch.sh status
```

---

## Деплой

### В Eclipse 2026-03 (dev/test)

```bash
# 1. Скопировать jar
cp plugin17-test_1.0.0.jar /opt/eclipse-latest/plugins/

# 2. Добавить в bundles.info
echo "plugin17-test,1.0.0.qualifier,plugins/plugin17-test_1.0.0.jar,4,true" \
  >> /opt/eclipse-latest/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info

# 3. Перезапустить через systemd
systemctl --user restart eclipse-latest.service
```

### В EDT (production)

```bash
EDT_DIR="/opt/1C/1CE/components/1c-edt-2025.1.5+34-x86_64"

# 1. Скопировать jar
sudo cp plugin17-test_1.0.0.jar "$EDT_DIR/plugins/"

# 2. Добавить в bundles.info
echo "plugin17-test,1.0.0.qualifier,plugins/plugin17-test_1.0.0.jar,4,true" \
  | sudo tee -a "$EDT_DIR/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"

# 3. Перезапустить EDT (НЕ использовать -clean!)
ps aux | grep "[1]cedt" | awk '{print $2}' | xargs -r kill -9
sleep 2
rm -f /home/ai/workspace-edt2025/.metadata/.lock
DISPLAY=:1 XAUTHORITY=/home/ai/.Xauthority nohup "$EDT_DIR/1cedt" \
  -data /home/ai/workspace-edt2025 >/tmp/edt.log 2>&1 &
```

---

## Ключевые особенности и грабли

### 1. Eclipse не видит файлы, созданные на файловой системе

Если создать `.java` файл через `Write` tool или `echo > file`, Eclipse **не подхватит** его автоматически.

**Решение:**
- Использовать `eclipse-coder` MCP (`createFile` tool)
- Или refresh через Eclipse UI (F5)
- Или перезапустить Eclipse

### 2. bundles.info — обязательно

Просто положить jar в `plugins/` **недостаточно**. OSGi читает `bundles.info` при старте. Без записи в нём плагин не загрузится.

### 3. Перезапуск без -clean

EDT **нельзя** перезапускать с `-clean` — ломается secure storage, отладка 1С перестаёт работать.

**Правильно:**
```bash
kill EDT && rm .metadata/.lock && start EDT
```

**Неправильно:**
```bash
EDT -clean  # ← НЕ ДЕЛАТЬ
```

### 4. StepOver не работает из event dispatch thread

`handleDebugEvents()` вызывается на debug event dispatch thread. Вызов `thread.stepOver()` из него **блокируется**.

**Решение:** вызывать stepOver на отдельном потоке:
```java
new Thread(() -> thread.stepOver(), "tracer-autostep").start();
```

Или напрямую (в pipeline версии — работает, т.к. stepOver асинхронный).

### 5. Thread creation overhead

`new Thread()` на каждый stepOver = ~1ms overhead. При 5ms/step это 20% потерь.

**Решение (pipeline):** вызывать stepOver напрямую, запись делегировать writer-thread через BlockingQueue.

### 6. Per-workspace конфигурация

`Platform.getInstanceLocation().getURL().getPath()` возвращает workspace path. Конфиг читается из `{workspace}/.edt-debug-tracer/tracer.properties`.

**Важно:** НЕ использовать `System.getProperty("user.home")` — он общий для всех Eclipse instance.

### 7. AssistAI MCP требует initialize

Нельзя просто слать `tools/call`. Нужна последовательность:
1. `initialize` → получить `Mcp-Session-Id` из заголовка ответа
2. `tools/call` с заголовком `Mcp-Session-Id`

### 8. Git push может потребовать pull --rebase

Если в remote есть коммиты, которых нет локально:
```bash
git pull --rebase origin main && git push origin main
```

### 9. MANIFEST.MF — Bundle-Activator

Поле `Bundle-Activator` должно указывать на класс, реализующий `BundleActivator`. Если класс не найден — плагин не загрузится, но **ошибки в логах может не быть**.

**Проверка:** `curl http://localhost:PORT/mcp/health` — если не отвечает, плагин не загрузился.

### 10. JDIDebugModel — NoClassDefFoundError в OSGi

Прямой импорт `org.eclipse.jdt.debug.core.JDIDebugModel` вызывает `NoClassDefFoundError` в runtime, даже если `Require-Bundle` и `Import-Package` указаны в MANIFEST.MF.

**Решение:** reflective class loading через bundle classloader:
```java
Bundle bundle = Platform.getBundle("org.eclipse.jdt.debug");
bundle.start(Bundle.START_ACTIVATION_POLICY);
Class<?> modelClass = bundle.loadClass("org.eclipse.jdt.debug.core.JDIDebugModel");
Method method = modelClass.getMethod("createLineBreakpoint", ...);
IBreakpoint bp = (IBreakpoint) method.invoke(null, resource, typeName, line, -1, -1, 0, true, null);
```

### 11. Java Properties не поддерживает inline-комментарии

`key=value # comment` — `#` считается частью значения. Комментарии должны быть **на отдельных строках**:
```properties
# Правильно:
port=18060
# Неправильно:
port=18060    # порт плагина
```

### 12. toggleBreakpoint — это переключатель

MCP tool `toggleBreakpoint` **удаляет** breakpoint если он уже существует на этой строке. Для гарантированной установки: сначала `removeAllBreakpoints`, потом `toggleBreakpoint`.

### 13. Активная диагностика через MCP

При возникновении ошибок — **не угадывать**, а проверять через MCP:
- `eclipse-ide` → `getCompilationErrors` — ошибки компиляции
- `eclipse-runner` → `listBreakpoints`, `listActiveLaunches` — состояние debug
- `curl /mcp/debug/status` — статус через наш API

---

## Восстановление контекста

После очистки сессии:

1. **Прочитать этот файл** (`docs/AI-CONTEXT.md`)
2. **Проверить окружение:**
   ```bash
   systemctl --user status eclipse-latest.service
   curl http://localhost:18060/mcp/health
   curl http://localhost:18080/mcp/health
   ```
3. **Прочитать README.md и NEXT-STEPS.md**
4. **Выбрать задачу из NEXT-STEPS.md** и начать работу

---

## Полезные команды

```bash
# Проверить порты
ss -tlnp | grep -E "18060|18080|8124|8765"

# Логи Eclipse
journalctl --user -u eclipse-latest.service -n 50

# Логи EDT
tail -50 /home/ai/workspace-edt2025/.metadata/.log

# Git status
cd /home/ai/workspace-edt2025/smallbase_test_db/tests/qa/debug_logger/edt-debug-tracer
git log --oneline -5
```
