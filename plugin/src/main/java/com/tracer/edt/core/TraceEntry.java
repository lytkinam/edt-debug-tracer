package com.tracer.edt.core;

import java.time.Instant;

/**
 * One trace record: module/procedure name, line number, thread name, timestamp.
 */
public class TraceEntry {

    private final String procedure;   // frame name (module.procedure)
    private final int    line;
    private final String thread;
    private final Instant timestamp;

    public TraceEntry(String procedure, int line, String thread) {
        this.procedure = procedure;
        this.line      = line;
        this.thread    = thread;
        this.timestamp = Instant.now();
    }

    public String getProcedure()  { return procedure; }
    public int    getLine()       { return line; }
    public String getThread()     { return thread; }
    public Instant getTimestamp() { return timestamp; }

    /** Simple JSON serialization (no external deps). */
    public String toJson() {
        return String.format(
            "{\"procedure\":\"%s\",\"line\":%d,\"thread\":\"%s\",\"timestamp\":\"%s\"}",
            escape(procedure), line, escape(thread), timestamp.toString()
        );
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
