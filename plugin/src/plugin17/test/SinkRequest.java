package plugin17.test;

import java.util.List;

/**
 * Input contract for the /sink/run endpoint.
 *
 * All fields are optional except project + mainClass.
 * The sink uses sensible defaults when fields are omitted.
 */
public class SinkRequest {

    /** Eclipse project name (must exist in workspace). */
    public String project = "";

    /** Fully-qualified main class, e.g. "com.example.Main" */
    public String mainClass = "";

    /** Optional program arguments passed to the launched process. */
    public String[] args = new String[0];

    /**
     * Optional list of source locations where the sink should set breakpoints
     * before launching, in addition to any already set in Eclipse.
     *
     * Each entry is a string in the form  "ClassName:lineNumber",
     * e.g. ["com.example.Foo:42", "com.example.Bar:17"].
     *
     * If empty the sink inherits whatever breakpoints are already present
     * in the workspace.
     */
    public List<String> breakpoints = List.of();

    /**
     * Maximum number of debug steps to collect (0 = unlimited).
     * Default: 500 — enough to understand a method without flooding the AI context.
     */
    public int maxSteps = 500;

    /**
     * Step type: "into" | "over" | "return".
     * Default: "into" — gives the deepest possible trace.
     */
    public String stepType = "into";

    /**
     * Timeout in milliseconds to wait for the debug session to reach
     * the first suspended state before giving up.
     * Default: 15 000 ms.
     */
    public long timeoutMs = 15_000L;

    /**
     * Whether to terminate the debug session after the trace completes.
     * Default: true — keeps the sink side-effect free.
     */
    public boolean terminateAfter = true;

    /**
     * Whether to save the full trace as JSON to disk.
     * Default: true.
     */
    public boolean saveJson = true;

    // -------------------------------------------------------------------------
    // Parse from a minimal hand-rolled JSON string (no external dependencies).
    // -------------------------------------------------------------------------

    public static SinkRequest fromJson(String json) {
        SinkRequest r = new SinkRequest();
        if (json == null || json.isBlank()) return r;

        r.project    = jsonString(json, "project",   r.project);
        r.mainClass  = jsonString(json, "mainClass",  r.mainClass);
        r.stepType   = jsonString(json, "stepType",   r.stepType);
        r.maxSteps   = jsonInt(json, "maxSteps",      r.maxSteps);
        r.timeoutMs  = jsonLong(json, "timeoutMs",    r.timeoutMs);
        r.terminateAfter = jsonBool(json, "terminateAfter", r.terminateAfter);
        r.saveJson   = jsonBool(json, "saveJson",     r.saveJson);
        r.args       = jsonStringArray(json, "args");
        r.breakpoints = java.util.Arrays.asList(jsonStringArray(json, "breakpoints"));
        return r;
    }

    // --- tiny JSON helpers (no Gson/Jackson dep) ---

    private static String jsonString(String json, String key, String def) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return def;
        int c = json.indexOf(':', i + key.length() + 2);
        if (c < 0) return def;
        int q1 = json.indexOf('"', c + 1);
        if (q1 < 0) return def;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return def;
        return json.substring(q1 + 1, q2);
    }

    private static int jsonInt(String json, String key, int def) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return def;
        int c = json.indexOf(':', i + key.length() + 2);
        if (c < 0) return def;
        int s = c + 1;
        while (s < json.length() && !Character.isDigit(json.charAt(s)) && json.charAt(s) != '-') s++;
        int e = s;
        if (e < json.length() && json.charAt(e) == '-') e++;
        while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        try { return Integer.parseInt(json.substring(s, e)); } catch (NumberFormatException ex) { return def; }
    }

    private static long jsonLong(String json, String key, long def) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return def;
        int c = json.indexOf(':', i + key.length() + 2);
        if (c < 0) return def;
        int s = c + 1;
        while (s < json.length() && !Character.isDigit(json.charAt(s)) && json.charAt(s) != '-') s++;
        int e = s;
        if (e < json.length() && json.charAt(e) == '-') e++;
        while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        try { return Long.parseLong(json.substring(s, e)); } catch (NumberFormatException ex) { return def; }
    }

    private static boolean jsonBool(String json, String key, boolean def) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return def;
        int c = json.indexOf(':', i + key.length() + 2);
        if (c < 0) return def;
        String tail = json.substring(c + 1).trim();
        if (tail.startsWith("true"))  return true;
        if (tail.startsWith("false")) return false;
        return def;
    }

    private static String[] jsonStringArray(String json, String key) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return new String[0];
        int c  = json.indexOf(':', i + key.length() + 2);
        int a1 = json.indexOf('[', c);
        int a2 = json.indexOf(']', a1);
        if (a1 < 0 || a2 < 0) return new String[0];
        String inner = json.substring(a1 + 1, a2).trim();
        if (inner.isBlank()) return new String[0];
        // split by commas outside quotes
        java.util.List<String> items = new java.util.ArrayList<>();
        int pos = 0;
        while (pos < inner.length()) {
            int q1 = inner.indexOf('"', pos);
            if (q1 < 0) break;
            int q2 = inner.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            items.add(inner.substring(q1 + 1, q2));
            pos = q2 + 1;
        }
        return items.toArray(new String[0]);
    }
}
