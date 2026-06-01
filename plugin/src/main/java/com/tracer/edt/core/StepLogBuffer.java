package com.tracer.edt.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe buffer for trace entries.
 * Controlled by start/stop session commands from MCP server.
 */
public class StepLogBuffer {

    private final CopyOnWriteArrayList<TraceEntry> entries = new CopyOnWriteArrayList<>();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private volatile String sessionId = null;

    /** Start a new recording session. Returns false if already running. */
    public boolean startSession(String sessionId) {
        if (!recording.compareAndSet(false, true)) return false;
        entries.clear();
        this.sessionId = sessionId;
        return true;
    }

    /** Stop recording and return snapshot of collected entries. */
    public List<TraceEntry> stopSession() {
        recording.set(false);
        List<TraceEntry> snapshot = new ArrayList<>(entries);
        entries.clear();
        return Collections.unmodifiableList(snapshot);
    }

    public void add(TraceEntry entry) {
        if (recording.get()) entries.add(entry);
    }

    public boolean isRecording() { return recording.get(); }
    public int     size()        { return entries.size(); }
    public String  getSessionId(){ return sessionId; }
}
