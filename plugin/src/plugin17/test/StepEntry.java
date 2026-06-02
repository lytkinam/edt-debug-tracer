package plugin17.test;

public record StepEntry(
    String procedure,
    int line,
    String module,
    String threadName,   // (1.3) thread.getName()
    int threadId,        // identityHashCode (optional)
    long timestamp,
    String variablesJson // nullable, JSON object or null
) {
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"procedure\":\"").append(esc(procedure))
          .append("\",\"line\":").append(line)
          .append(",\"module\":\"").append(esc(module))
          .append("\",\"thread_name\":\"").append(esc(threadName))
          .append("\",\"thread_id\":").append(threadId)
          .append(",\"ts\":").append(timestamp);
        if (variablesJson != null) {
            sb.append(",\"variables\":").append(variablesJson);
        }
        sb.append("}");
        return sb.toString();
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
