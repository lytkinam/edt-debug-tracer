package com.tracer.edt.core;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the currently active trace session ID.
 * Thread-safe.
 */
public class TraceSessionManager {

    private final AtomicReference<String> activeSessionId = new AtomicReference<>(null);
    private volatile long stepCount = 0;

    public boolean start(String sessionId) {
        return activeSessionId.compareAndSet(null, sessionId);
    }

    public String stop() {
        stepCount = 0;
        return activeSessionId.getAndSet(null);
    }

    public String getActiveSessionId() {
        return activeSessionId.get();
    }

    public boolean isActive() {
        return activeSessionId.get() != null;
    }

    public void incrementSteps() { stepCount++; }
    public long getStepCount() { return stepCount; }
}
