package com.tracer.edt.core;

/**
 * A post-processed trace entry. May represent a single step, a repeat block, or a loop pattern.
 */
public class CollapsedTraceEntry {

    public enum Kind { STEP, REPEAT, LOOP }

    private final Kind kind;
    private final String procedure;
    private final int line;
    private final String module;
    private final int repeatCount;
    private final int patternLen;
    private final long ts;

    public CollapsedTraceEntry(Kind kind, String procedure, int line, String module,
                                int repeatCount, int patternLen, long ts) {
        this.kind = kind;
        this.procedure = procedure;
        this.line = line;
        this.module = module;
        this.repeatCount = repeatCount;
        this.patternLen = patternLen;
        this.ts = ts;
    }

    public Kind getKind() { return kind; }
    public String getProcedure() { return procedure; }
    public int getLine() { return line; }
    public String getModule() { return module; }
    public int getRepeatCount() { return repeatCount; }
    public int getPatternLen() { return patternLen; }
    public long getTs() { return ts; }

    public String toJson() {
        return "{\"kind\":\"" + kind.name().toLowerCase() + "\""
            + ",\"procedure\":\"" + esc(procedure) + "\""
            + ",\"line\":" + line
            + ",\"module\":\"" + esc(module) + "\""
            + ",\"repeat_count\":" + repeatCount
            + ",\"pattern_len\":" + patternLen
            + ",\"ts\":" + ts + "}";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
