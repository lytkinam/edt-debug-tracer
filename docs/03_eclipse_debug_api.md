# 03. Eclipse Debug API

## Key interfaces

### IDebugEventSetListener
```java
public interface IDebugEventSetListener {
    void handleDebugEvents(DebugEvent[] events);
}
```
Registered via `DebugPlugin.getDefault().addDebugEventListener(...)`.
Called on the debug event dispatch thread — keep it fast, offload to queue.

### DebugEvent kinds
| Constant | When |
|---|---|
| `SUSPEND` | Thread stopped (breakpoint, step, exception) |
| `RESUME`  | Thread continues |
| `TERMINATE` | Debug session ended |
| `CREATE`  | Debug target or thread created |

### Reading the stack frame
```java
if (event.getKind() == DebugEvent.SUSPEND) {
    Object src = event.getSource();
    if (src instanceof IThread thread) {
        IStackFrame frame = thread.getTopStackFrame();
        if (frame != null) {
            String name   = frame.getName();         // "ПроцедураA line: 42"
            int    line   = frame.getLineNumber();   // 42
            String module = frame.getLaunch()        // approx module name
                                 .getLaunchConfiguration().getName();
        }
    }
}
```

### EDT-specific: BSL frame
EDT wraps Eclipse IStackFrame in its own BSL implementation.
The exact class is `com._1c.g5.v8.dt.debug.core.model.IBslStackFrame` (internal API).
Safe approach: cast to `IStackFrame`, read `getName()` and `getLineNumber()`;
parse procedure name from `getName()` with a regex `^(.*) line: (\\d+)$`.

### Step control
```java
thread.stepInto();   // F5
thread.stepOver();   // F6
thread.resume();     // F8
```

### Thread safety
Debug events arrive on a dedicated thread.
Never perform blocking I/O inside `handleDebugEvents`.
Use `AsyncTraceWriter` queue pattern.
