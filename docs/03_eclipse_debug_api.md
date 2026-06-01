# 03. Eclipse Debug API

## Ключевые интерфейсы

### DebugPlugin
`org.eclipse.debug.core.DebugPlugin` — синглтон, точка входа:
- `DebugPlugin.getDefault().addDebugEventListener(listener)` — подписаться на события.
- `DebugPlugin.getDefault().removeDebugEventListener(listener)` — отписаться.

### IDebugEventSetListener
```java
public interface IDebugEventSetListener {
    void handleDebugEvents(DebugEvent[] events);
}
```
Вызывается синхронно при каждом событии отладчика.

### DebugEvent
Поля:
- `getKind()` — `SUSPEND`, `RESUME`, `TERMINATE`, `CREATE`, `CHANGE`.
- `getDetail()` — уточнение: `STEP_END`, `BREAKPOINT`, `CLIENT_REQUEST`.
- `getSource()` — объект, породивший событие (чаще всего `IThread`).

### IThread / IStackFrame
```java
IThread thread = (IThread) event.getSource();
IStackFrame frame = thread.getTopStackFrame();
String name = frame.getName();      // имя процедуры/функции
int    line = frame.getLineNumber(); // номер строки
```

## Паттерн обработчика

```java
@Override
public void handleDebugEvents(DebugEvent[] events) {
    for (DebugEvent event : events) {
        if (event.getKind() == DebugEvent.SUSPEND
            && event.getDetail() == DebugEvent.STEP_END) {
            try {
                IThread thread = (IThread) event.getSource();
                IStackFrame frame = thread.getTopStackFrame();
                if (frame != null) {
                    buffer.add(new TraceEntry(
                        frame.getName(),
                        frame.getLineNumber(),
                        thread.getName(),
                        Instant.now()
                    ));
                }
            } catch (DebugException e) {
                // логировать, не пробрасывать
            }
        }
    }
}
```

## Зависимости MANIFEST.MF

```
Require-Bundle: org.eclipse.debug.core,
 org.eclipse.core.runtime,
 com._1c.g5.v8.dt.debug.core;resolution:=optional
```

`com._1c.g5.v8.dt.debug.core` помечать `resolution:=optional` — чтобы плагин не падал, если EDT не установлен (например, при unit-тестах).

## Полезные события

| Kind | Detail | Когда |
|------|--------|-------|
| SUSPEND | STEP_END | после каждого шага F6/F7/F8 |
| SUSPEND | BREAKPOINT | попали на точку останова |
| SUSPEND | CLIENT_REQUEST | пауза по запросу (Pause button) |
| RESUME | STEP_OVER | пользователь нажал F6 |
| TERMINATE | — | сеанс отладки завершён |
