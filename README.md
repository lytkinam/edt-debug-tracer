# edt-debug-tracer

Eclipse EDT plugin: трассировка шагов BSL-кода через Eclipse Debug API + локальный MCP HTTP-сервер для интеграции с Vanessa и AI-агентом.

## Что это

Плагин подписывается на события Eclipse Debug API (`SUSPEND/RESUME/TERMINATE`), при каждом шаге отладки читает верхний `IStackFrame` (модуль, строка, процедура) и пишет трейс в буфер. Vanessa и AI-агент управляют трассировкой через локальный HTTP API (`localhost:18080/mcp`).

## Быстрый старт

1. Установить **Eclipse IDE for RCP Developers** (Java 17+).
2. Настроить **Target Platform** на каталог `plugins/` установленного 1C:EDT.
3. Импортировать проект: `File → Import → Existing Projects into Workspace`, выбрать папку `plugin/`.
4. Запустить конфигурацию `Eclipse Application` (второй экземпляр EDT).
5. Проверить: `curl http://localhost:18080/mcp/health`.

## Структура репозитория

```
docs/                     # Контекст, архитектура, API, настройка окружения
plugin/                   # Eclipse PDE проект (импортировать напрямую)
  META-INF/MANIFEST.MF
  plugin.xml
  .project / .classpath
  build.properties
  src/main/java/
    com/tracer/edt/
      core/               # Активатор, listener, буфер, TraceEntry
      mcp/                # MCP HTTP-сервер (McpHttpServer.java)
tests/                    # Python-тесты HTTP API
.github/workflows/        # CI (build.yml)
IMPORT-CHECKLIST.md       # Чеклист прямого импорта в Eclipse PDE
```

## Документация

| Файл | Что внутри |
|------|------------|
| [docs/01_context.md](docs/01_context.md) | Зачем нужен трейс BSL-кода |
| [docs/02_architecture.md](docs/02_architecture.md) | Схема компонентов |
| [docs/03_eclipse_debug_api.md](docs/03_eclipse_debug_api.md) | Eclipse Debug API: события, стек, шаги |
| [docs/04_mcp_server.md](docs/04_mcp_server.md) | MCP HTTP API: эндпоинты, форматы JSON |
| [docs/05_vanessa_integration.md](docs/05_vanessa_integration.md) | Интеграция с Vanessa |
| [docs/06_dev_environment.md](docs/06_dev_environment.md) | Настройка окружения разработки |
| [docs/07_direct_debug_protocol.md](docs/07_direct_debug_protocol.md) | Прямое подключение к HTTP debug endpoint 1С |

## MCP API

```
GET  /mcp/health        — статус сервера
GET  /mcp/status        — текущий статус трассировки
POST /mcp/start         — начать запись трейса
POST /mcp/stop          — остановить запись, получить JSON-лог
```

## Требования

- Java 17 (компиляция плагина)
- Eclipse IDE for RCP Developers 2024+
- 1C:EDT 2024.x или 2026.1
- Python 3.9+ (для тестов)
