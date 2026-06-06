package plugin17.test;

import java.util.List;

/**
 * Output contract for the /sink/run endpoint.
 *
 * This is the only thing the sink returns — a fully self-contained
 * description of what happened during the debug run.
 */
public class SinkResponse {

    public enum Status { OK, ERROR, TIMEOUT, NO_SESSION }

    public Status  status     = Status.OK;
    public String  sessionId  = "";
    public int     stepCount  = 0;
    public String  jsonPath   = "";
    public String  error      = "";
    public long    durationMs = 0;

    /** Full step trace — same StepEntry records as the rest of the plugin. */
    public List<StepEntry> steps = List.of();

    // -------------------------------------------------------------------------
    // Serialise to JSON without external dependencies.
    // -------------------------------------------------------------------------

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"status\":\"").append(status.name()).append("\"");
        sb.append(",\"sessionId\":\"").append(esc(sessionId)).append("\"");
        sb.append(",\"stepCount\":").append(stepCount);
        sb.append(",\"jsonPath\":\"").append(esc(jsonPath)).append("\"");
        sb.append(",\"durationMs\":").append(durationMs);
        if (!error.isEmpty()) {
            sb.append(",\"error\":\"").append(esc(error)).append("\"");
        }
        sb.append(",\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(steps.get(i).toJson());
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // -------------------------------------------------------------------------
    // Static factory helpers.
    // -------------------------------------------------------------------------

    public static SinkResponse error(String message) {
        SinkResponse r = new SinkResponse();
        r.status = Status.ERROR;
        r.error  = message;
        return r;
    }

    public static SinkResponse timeout(long durationMs) {
        SinkResponse r = new SinkResponse();
        r.status     = Status.TIMEOUT;
        r.error      = "Debug session did not reach suspended state within timeout";
        r.durationMs = durationMs;
        return r;
    }
}
