package com.tracer.edt.core;

/**
 * A single raw BSL execution step: procedure name, line, module, thread.
 */
public class TraceEntry {

    private final String procedure;
    private final int line;
    private final String module;
    private final long threadId;
    private final long ts;

    public TraceEntry(String procedure, int line, String module, long threadId) {
        this.procedure = procedure;
        this.line = line;
        this.module = module;
        this.threadId = threadId;
        this.ts = System.currentTimeMillis();
    }

    /** For tests: explicit timestamp */
    public TraceEntry(String procedure, int line, String module, long threadId, long ts) {
        this.procedure = procedure;
        this.line = line;
        this.module = module;
        this.threadId = threadId;
        this.ts = ts;
    }

    public String getProcedure() { return procedure; }
    public int getLine() { return line; }
    public String getModule() { return module; }
    public long getThreadId() { return threadId; }
    public long getTs() { return ts; }

    public String toJson() {
        return "{\"procedure\":\"" + esc(procedure) + "\""
            + ",\"line\":" + line
            + ",\"module\":\"" + esc(module) + "\""
            + ",\"thread_id\":" + threadId
            + ",\"ts\":" + ts + "}";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override public String toString() { return procedure + ":" + line + " [" + module + "]"; }
}
