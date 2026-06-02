package plugin17.test;

public record StepEntry(
    String procedure,
    int line,
    String module,
    long threadId,
    long timestamp
) {
    public String toJson() {
        return "{\"procedure\":\"" + esc(procedure)
            + "\",\"line\":" + line
            + ",\"module\":\"" + esc(module)
            + "\",\"thread_id\":" + threadId
            + ",\"ts\":" + timestamp + "}";
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
