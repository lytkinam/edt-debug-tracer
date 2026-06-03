# EDT Debug Tracer — Отладка на проде (EDT 2025.1.5)

Тестирование плагина v9.2 на EDT с реальной 1С-отладкой. Проект: `test_db`.

## Окружение

| Параметр | Значение |
|----------|----------|
| EDT | 2025.1.5+34-x86_64 |
| JDK | Axiom JDK 17.0.16+12 |
| Порт трейсера | 18080 |
| Порт codepilot1c | 8765 |
| Workspace | /home/ai/workspace-edt2025 |
| Display | :1 (VNC) |
| Проект 1С | test_db (Толстый клиент) |
| Launch config | `test_db fat client` |

## Результаты тестирования

### ✅ Работает

| # | Endpoint | Результат | Комментарий |
|---|----------|-----------|-------------|
| 1 | `GET /mcp/health` | `ok: true, port: 18080` | Плагин загружается в EDT |
| 2 | `POST /mcp/start` | `started: true` | Запись трейса запущена |
| 3 | `POST /mcp/run` | `autoStep: true` | Auto-step работает на 1С |
| 4 | `POST /mcp/stop` | `count: 21, totalSteps: 21` | 21 шаг записан |
| 5 | `POST /mcp/debug/launch` | `launched: true` | 1С Толстый клиент запущен в debug |
| 6 | `GET /mcp/debug/status` | `suspended`, `running`, `terminated` | Все состояния корректны |
| 7 | `POST /mcp/debug/step` (over) | line 23→24 | Шаг через строку |
| 8 | `POST /mcp/debug/step` (into) | line 24→25 | Шаг внутрь |
| 9 | `GET /mcp/debug/stack` | 2 фрейма | Стек вызовов: ДобавитьПараметр→УстановкаПараметров |
| 10 | `POST /mcp/debug/resume` | `ok: true` | Продолжение выполнения |
| 11 | `POST /mcp/debug/terminate` | `terminated: true` | Завершение debug-сессии |
| 12 | `GET /mcp/sessions/list` | 2 сессии | Список всех сессий из SQLite |
| 13 | `GET /mcp/sessions/last` | session_id, steps, timestamps | Последняя сессия |
| 14 | `GET /mcp/sessions/steps` | 16 шагов с полями | Лог конкретной сессии |
| 15 | `GET /mcp/status` | recording, sqlite, sessions | Расширенный статус |

### ✅ Данные трейса (12 полей)

Все 12 полей StepEntry заполняются корректно для 1С BSL:

| Поле | Пример | Статус |
|------|--------|--------|
| `procedure` | `МодульСеанса.УстановкаПараметровСеанса(ТребуемыеПараметры = ) строка: 3` | ✅ |
| `line` | `3` | ✅ |
| `module` | `L/test_db/src/Configuration/SessionModule.bsl` | ✅ |
| `thread_name` | `Сервер [admin]` | ✅ |
| `thread_id` | `1256102702` | ✅ |
| `ts` | `1780490290779` | ✅ |
| `char_start` | `-1` | ✅ (1С не даёт char position) |
| `char_end` | `-1` | ✅ |
| `stack_depth` | `1` | ✅ |
| `parent_seq` | `-1` | ✅ |
| `stack` | `[{procedure, line}]` | ✅ |
| `variables` | `{ТребуемыеПараметры: {type: "Массив", value: "Массив"}}` | ✅ |

### Переменные 1С (детали)

```json
{
  "ТребуемыеПараметры": {"type": "Массив", "value": "Массив"},
  "ТребуемыеПараметры2": {"type": "Неопределено", "value": "Неопределено"},
  "ТребуемыеПараметры3": {"type": "Неопределено", "value": "Неопределено"}
}
```

Типы 1С (Неопределено, Массив) захватываются корректно.

### Процедуры в трейсе

```
  15x МодульСеанса.УстановкаПараметровСеанса
   6x МодульСеанса.ДобавитьТребуеммыйПараметр
```

### ❌ Не работает

| # | Endpoint | Проблема |
|---|----------|----------|
| 1 | `POST /mcp/breakpoints/set` | `set: 0` — `JDIDebugModel.createLineBreakpoint()` работает только для Java. Для 1С BSL нужен `BslLineBreakpoint` из `com._1c.g5.v8.dt.debug.core` |
| 2 | `POST /mcp/breakpoints/clear` | Работает, но breakpoints не ставятся |
| 3 | `GET /mcp/breakpoints/list` | Пустой список (нечего показывать) |
| 4 | `project_name` в сессиях | Пустой — 1С launch config не имеет `org.eclipse.jdt.launching.PROJECT_ATTR` |

### Причины и решения для breakpoints

**Проблема:** `TracerBreakpoints.java` использует `JDIDebugModel.createLineBreakpoint()` — это Java-specific API. Для 1С BSL файлов нужен другой механизм:

```
com._1c.g5.v8.dt.debug.core.model.breakpoints.BslLineBreakpoint
com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointFactory
```

**Возможные подходы:**
1. Использовать `IBslBreakpointFactory` из `com._1c.g5.v8.dt.debug.core` (internal API)
2. Создавать marker напрямую через `IMarker` с типом BSL breakpoint
3. Интеграция с UI EDT — вызвать команду IDE для установки breakpoint

**Сложность:** Высокая — internal API 1С, может меняться между версиями EDT.

## Полный сценарий (воспроизведённый)

```bash
# 1. Запустить запись
curl -X POST http://localhost:18080/mcp/start

# 2. Запустить 1С в debug (через существующую launch config)
curl -X POST http://localhost:18080/mcp/debug/launch \
  -d '{"project":"test_db fat client","mainClass":""}'

# 3. Auto-step (15 шагов)
curl -X POST http://localhost:18080/mcp/run -d '{"steps":15}'

# 4. Остановить запись
curl -X POST http://localhost:18080/mcp/stop

# 5. Получить последнюю сессию
curl http://localhost:18080/mcp/sessions/last

# 6. Получить шаги сессии
curl "http://localhost:18080/mcp/sessions/steps?session_id=s-..."

# 7. Управление отладкой
curl http://localhost:18080/mcp/debug/status
curl -X POST http://localhost:18080/mcp/debug/step -d '{"type":"over"}'
curl -X POST http://localhost:18080/mcp/debug/step -d '{"type":"into"}'
curl -X POST http://localhost:18080/mcp/debug/resume
curl http://localhost:18080/mcp/debug/stack
curl -X POST http://localhost:18080/mcp/debug/terminate
```

## Вывод

**14 из 17 endpoints работают на проде с 1С-отладкой.** Не работают только breakpoints (3 endpoint-а) — требует реализации `BslLineBreakpoint` из 1С debug core.
