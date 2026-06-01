package com.tracer.edt.core;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

/**
 * Listens to Eclipse debug events.
 * On SUSPEND + STEP_END or BREAKPOINT: reads top stack frame and writes a TraceEntry to buffer.
 */
public class DebugTracerListener implements IDebugEventSetListener {

    private final StepLogBuffer buffer;

    public DebugTracerListener(StepLogBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        if (!buffer.isRecording()) return;

        for (DebugEvent event : events) {
            if (event.getKind() != DebugEvent.SUSPEND) continue;
            // Обрабатываем: шаг завершён или точка останова
            int detail = event.getDetail();
            if (detail != DebugEvent.STEP_END && detail != DebugEvent.BREAKPOINT) continue;

            Object source = event.getSource();
            if (!(source instanceof IThread)) continue;

            IThread thread = (IThread) source;
            try {
                IStackFrame frame = thread.getTopStackFrame();
                if (frame == null) continue;

                TraceEntry entry = new TraceEntry(
                    frame.getName(),
                    frame.getLineNumber(),
                    thread.getName()
                );
                buffer.add(entry);
            } catch (DebugException e) {
                // Молча пропускаем — не критично потерять одну запись
            }
        }
    }
}
