package plugin17.test;

import java.util.List;

public record StepEntry(
    String procedure,
    int line,
    String module,
    String threadName,   // (1.3) thread.getName()
    int threadId,        // identityHashCode (optional)
    long timestamp,
    int charStart,       // (1.4) frame.getCharStart()
    int charEnd,         // (1.4) frame.getCharEnd()
    int stackDepth,      // (1.5) frames.length
    int parentSeq,       // (1.5) seq of caller step (-1 if none)
    String stackJson,    // (1.5) full stack as JSON array or null
    String variablesJson // nullable, JSON object or null
) {
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"procedure\":\"").append(esc(procedure))
          .append("\",\"line\":").append(line)
          .append(",\"module\":\"").append(esc(module))
          .append("\",\"thread_name\":\"").append(esc(threadName))
          .append("\",\"thread_id\":").append(threadId)
          .append(",\"ts\":").append(timestamp)
          .append(",\"char_start\":").append(charStart)
          .append(",\"char_end\":").append(charEnd)
          .append(",\"stack_depth\":").append(stackDepth)
          .append(",\"parent_seq\":").append(parentSeq);
        if (stackJson != null) {
            sb.append(",\"stack\":").append(stackJson);
        }
        if (variablesJson != null) {
            sb.append(",\"variables\":").append(variablesJson);
        }
        sb.append("}");
        return sb.toString();
    }

    /** Сериализует список шагов в JSON-массив. Используется TracerSink. */
    public static String entriesToJson(List<StepEntry> entries) {
        if (entries == null || entries.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(entries.get(i).toJson());
        }
        sb.append("]");
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
