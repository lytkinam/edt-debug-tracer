# EDT Debug Tracer — Next Steps

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

**Результат:** каждая запись будет содержать путь к модулю (CommonModules/MyModule/Module.bsl).

### 1.2 Захват переменных стека

Добавить в StepEntry список переменных текущего фрейма:

```java
IVariable[] vars = frame.getVariables();
// → Map<String, String> variables (name → value)
```

**Опция в конфиге:** `captureVariables=true/false` (по умолчанию false — влияет на производительность).

### 1.3 Thread name вместо identityHashCode

Заменить `System.identityHashCode(thread)` на `thread.getName()`:

```java
String threadName = thread.getName(); // "Сервер [admin]", "Клиент" и т.д.
```

**Результат:** читаемые имена потоков в трейсе.

---

## Приоритет 2: Управление и контроль

### 2.1 Дедупликация записей

Убрать дубликаты (два одинаковых SUSPEND на одной строке). Фильтр: если `(procedure, line, threadId)` совпадает с предыдущей записью и Δts < 50ms — пропустить.

### 2.2 Фильтрация по модулям/процедурам

Конфиг:
```properties
includeModules=ОбщийМодуль.СтандартныеПодсистемы*,МодульСеанса
excludeProcedures=*.toString,*.hashCode
```

### 2.3 Лимит записей

```properties
maxEntries=10000
```

При достижении лимита — автоматический stop + flush в файл.

### 2.4 Endpoint `/mcp/status`

Текущий прогресс записи без остановки:
```json
{"recording":true, "entries":523, "autoStepping":true, "stepsRemaining":477}
```

---

## Приоритет 3: Интеграция

### 3.1 Интеграция с codepilot1c MCP

Трейсер работает параллельно с codepilot1c на разных портах. Python-клиент может:
- Через codepilot1c (8765): ставить breakpoints, управлять debug-сессией
- Через tracer (18080): запускать auto-step, получать трейс

**Единый Python-контроллер:**
```python
codepilot = MCPClient("http://localhost:8765/mcp")
tracer = TracerClient("http://localhost:18080/mcp")

codepilot.set_breakpoint(project, "CommonModules/MyModule/Module.bsl", 42)
codepilot.debug_launch(project, main_class)
tracer.start()
tracer.run(steps=500)
# ... отладка идёт ...
result = tracer.stop()  # → trace.json с 500 записями
```

### 3.2 Формат трейса: NDJSON

Вместо JSON-массива — NDJSON (по одной записи на строку). Позволяет:
- Читать файл потоково (не загружая весь в память)
- Дописывать записи инкрементально (без перезаписи)
- Легко парсить в Python (`for line in open("trace.json"):`)

### 3.3 WebSocket endpoint

Вместо polling `/mcp/health` — WebSocket для real-time уведомлений:
- `recording_started`
- `step_captured` (каждая запись)
- `recording_stopped`
- `auto_step_complete`

---

## Приоритет 4: Производительность

### 4.1 Асинхронная запись на диск

Сейчас файл пишется при `/mcp/stop`. Для длинных сессий (10000+ шагов):
- Writer-thread пишет NDJSON инкрементально
- Буферизация: flush каждые 100 записей или 500ms

### 4.2 Batch stepOver

Вместо одного stepOver за SUSPEND event — пакет из N stepOver:
```java
for (int i = 0; i < batchSize; i++) {
    thread.stepOver();
    // wait for SUSPEND, read frame, repeat
}
```

**Риск:** пропуск шагов если stepOver не генерирует SUSPEND.

### 4.3 Профилирование hot path

Замерить реальное время каждого этапа:
```java
long t1 = System.nanoTime();
String name = frame.getName();
long t2 = System.nanoTime();
int line = frame.getLineNumber();
long t3 = System.nanoTime();
// → лог: getName=2μs, getLineNumber=1μs
```

---

## Приоритет 5: Надёжность

### 5.1 Обработка завершения debug-сессии

Если debug-сессия завершилась (TERMINATE event) во время auto-step:
```java
if (event.getKind() == DebugEvent.TERMINATE) {
    autoStepping.set(false);
    // flush и stop
}
```

### 5.2 Восстановление после ошибок

Если `stepOver()` бросает `DebugException`:
- Логировать ошибку
- Попробовать resume
- Продолжить запись

### 5.3 Graceful shutdown

При остановке Eclipse (OSGi bundle stop):
- Flush буфера в файл
- Закрыть writer-thread
- Сохранить метаданные сессии

---

## Приоритет 6: UI и отладка самого трейсера

### 6.1 Eclipse View

Eclipse View с real-time отображением:
- Текущая позиция (процедура, строка)
- Счётчик записей
- Кнопки Start/Stop/Run
- Лог последних N записей

### 6.2 Preferences page

Eclipse Preferences для конфигурации:
- Port, output path
- captureVariables, maxEntries
- includeModules, excludeProcedures

### 6.3 Логирование событий

Системный лог (Eclipse `.metadata/.log`):
- `[tracer] Recording started, port=18080`
- `[tracer] Auto-step: 500 steps in 3500ms`
- `[tracer] Trace written: /path/to/trace.json (500 entries)`

---

## Идеи для исследования

### A. Сравнение с 1C:Enterprise debug protocol

1С имеет собственный debug protocol (HTTP, порт debug-сервера). Сравнить:
- Скорость step-by-step через JDI vs 1C debug protocol
- Полнота данных (переменные, типы, значения)
- Возможность remote debugging

### B. Conditional auto-step

Auto-step только для определённых условий:
```properties
autoStepCondition=module.contains("СтандартныеПодсистемы")
autoStepCondition=line > 100 && line < 200
```

### C. Call tree reconstruction

Из flat-треса восстановить дерево вызовов:
```
МодульСеанса.УстановкаПараметровСеанса:16
  └── СтандартныеПодсистемыСервер.УстановкаПараметровСеанса:45
       ├── :46
       ├── :47 (loop ×3)
       └── :61
```

### D. Diff-трейс

Сравнение двух трейсов (до/после изменения кода):
- Какие процедуры добавились/удалились
- Изменение количества шагов
- Изменение времени выполнения
