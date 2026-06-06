# TraceSink — AI Debug Trace Sink

> Branch: `java_debug_tracer`

## Концепция (Sinks, Not Pipes)

Трейсер в базовой ветке — многофункциональный сервис (запись, хранение, управление сессией, управление breakpoints, 17 endpoints).  
`TraceSink` — **чистый сток**: принимает запрос, выполняет отладку, возвращает трейс, останавливается. Никаких побочных эффектов за пределами контракта вызова.

## Когда использовать

Когда AI-агент:
- Не может понять поведение кода без выполнения
- Хочет получить полные значения переменных в конкретных точках
- Хочет собрать call tree без ручной расстановки breakpoints

## Endpoint

```
POST /sink/run
Content-Type: application/json
```

## Входной контракт (SinkRequest)

```json
{
  "project":       "my-eclipse-project",  // обязательно: имя проекта в workspace
  "mainClass":     "com.example.Main",     // обязательно: fully-qualified class
  "args":          ["arg1", "arg2"],        // опционально: аргументы программы
  "breakpoints":   [                        // опционально: расставить до запуска
    "com.example.Foo:42",
    "com.example.Bar:17"
  ],
  "maxSteps":      500,                    // опционально: лимит шагов (0 = ∞)
  "stepType":      "into",                 // опционально: into | over | return
  "timeoutMs":     15000,                  // опционально: таймаут ожидания suspend
  "terminateAfter": true,                  // опционально: завершить сессию после
  "saveJson":      true                    // опционально: сохранить JSON на диск
}
```

Проект **должен быть уже открыт в Eclipse workspace** — сток не создаёт проекты, он только запускает в debug режиме то, что уже есть.

## Выходной контракт (SinkResponse)

```json
{
  "status":     "OK",                  // OK | ERROR | TIMEOUT | NO_SESSION
  "sessionId":  "2026-06-06T18:00:00", // SQLite session ID (если storage есть)
  "stepCount":  247,
  "durationMs": 3412,
  "jsonPath":   "/workspace/.edt-debug-tracer/sink-myproject-com_example_main-1234567890.json",
  "steps": [
    {
      "procedure":   "main",
      "line":        10,
      "module":      "L/my-eclipse-project/src/com/example/Main.java",
      "thread_name": "main",
      "thread_id":   1234,
      "ts":          1780393616645,
      "char_start":  -1,
      "char_end":    -1,
      "stack_depth": 1,
      "parent_seq":  0,
      "stack":       [{"procedure":"main","line":10}],
      "variables":   {"args":{"type":"String[]","value":"[]"},"x":{"type":"int","value":"0"}}
    }
  ]
}
```

Каждый шаг содержит **полные значения переменных** на момент остановки — это именно то, что нужно AI для понимания состояния программы в конкретной точке.

## Сценарий использования AI-агентом

```
AI: не понимаю, почему метод processOrder возвращает неверный результат.

1. AI → POST /sink/run
   { "project": "order-service",
     "mainClass": "com.example.OrderServiceTest",
     "breakpoints": ["com.example.OrderProcessor:87"],
     "stepType": "into",
     "maxSteps": 200 }

2. Сток: запускает debug, ждёт suspend на строке 87,
   записывает 200 шагов с переменными, сохраняет JSON, завершает сессию.

3. AI ← SinkResponse { status: OK, steps: [...200 шагов с variables...] }

4. AI анализирует steps[], видит значения переменных на каждой строке,
   понимает логику, формулирует ответ.
```

## Предварительные требования

| Требование | Пояснение |
|------------|----------|
| Проект открыт в Eclipse | Сток работает только с проектами в текущем workspace |
| Код скомпилирован | Запуск в debug требует .class файлов |
| Eclipse Debug Framework активен | Стандартная конфигурация Eclipse/EDT |
| tracer.properties настроен | Порт, путь к SQLite, режим хранения |

## Отличие от /mcp/debug/launch + /mcp/start + /mcp/run + /mcp/stop

| Старый подход (pipe) | Новый подход (sink) |
|---------------------|--------------------|
| 4+ отдельных вызова | 1 вызов |
| Нужно управлять состоянием между вызовами | Нет state management на стороне клиента |
| Легко сделать ошибку в последовательности | Контракт: вход → выход |
| Оркестрация на стороне AI | Оркестрация инкапсулирована в стоке |

## Архитектура

```
                    POST /sink/run
                         │
                         ▼
                    TraceSink.run(SinkRequest)
                         │
          ┌──────────────┼───────────────────┐
          ▼              ▼                   ▼
   setBreakpoints   launchDebug        waitForSuspend
   (TracerBreakpoints)  (TracerListener)   (polling)
                         │
                    startRecording
                    startAutoStep(maxSteps)
                    waitForCompletion
                    stopRecording
                         │
               ┌─────────┴──────────┐
               ▼                    ▼
           saveJson           SQLite session ID
               │                    │
               └─────────┬──────────┘
                         ▼
                   SinkResponse
                   (HTTP response)
```
