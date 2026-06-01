package com.tracer.edt.core;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens to Eclipse Debug events. On each SUSPEND extracts the top BSL stack frame
 * and enqueues a TraceEntry. Must be fast - no blocking I/O.
 */
public class DebugTracerListener implements IDebugEventSetListener {

    // EDT frame name format: "ProcedureName line: 42" or just "ProcedureName"
    private static final Pattern FRAME_PATTERN = Pattern.compile("^(.+?)\\s+line:\\s*(\\d+)$");

    private final AsyncTraceWriter writer;
    private final TraceSessionManager sessionManager;

    public DebugTracerListener(AsyncTraceWriter writer, TraceSessionManager sessionManager) {
        this.writer = writer;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        String sessionId = sessionManager.getActiveSessionId();
        if (sessionId == null) return;

        for (DebugEvent event : events) {
            if (event.getKind() != DebugEvent.SUSPEND) continue;
            if (!(event.getSource() instanceof IThread thread)) continue;

            try {
                IStackFrame frame = thread.getTopStackFrame();
                if (frame == null) continue;

                String name = frame.getName();
                int line = frame.getLineNumber();
                String procedure = name;

                Matcher m = FRAME_PATTERN.matcher(name);
                if (m.matches()) {
                    procedure = m.group(1).trim();
                    try { line = Integer.parseInt(m.group(2)); } catch (NumberFormatException ignore) {}
                }

                long threadId = 0;
                try { threadId = Long.parseLong(thread.getName()); } catch (Exception ignore) {}

                writer.enqueue(sessionId, new TraceEntry(procedure, line, "main", threadId));
            } catch (Exception e) {
                // Suppress - never crash the debug thread
            }
        }
    }
}
