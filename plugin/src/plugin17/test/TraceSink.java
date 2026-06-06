package plugin17.test;

import org.eclipse.debug.core.DebugPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

/**
 * TraceSink — a pure sink for AI-driven debug tracing.
 *
 * CONTRACT
 * ========
 * Input  : SinkRequest  (project, mainClass, optional breakpoints, stepType, maxSteps)
 * Output : SinkResponse (session_id, steps[], json_path, status, durationMs)
 *
 * The sink:
 *   1. Sets any requested breakpoints in the Eclipse workspace.
 *   2. Launches a debug session for the given project + mainClass.
 *   3. Waits for the process to reach the first SUSPEND event (breakpoint or step).
 *   4. Enables recording on the shared TracerListener.
 *   5. Auto-steps for up to maxSteps iterations, collecting full variable state at each step.
 *   6. Stops recording, optionally saves JSON to disk.
 *   7. Optionally terminates the debug session.
 *   8. Returns all collected data as a SinkResponse.
 *
 * Side effects outside the call contract: NONE.
 * The sink does NOT trigger any other component. Storage is optional and scoped
 * to the call. The caller decides what to do with the response.
 */
public class TraceSink {

    private final TracerListener  tracer;
    private final TracerStorage   storage;   // may be null
    private final TracerBreakpoints bpManager;
    private final String          outputDir;

    public TraceSink(TracerListener tracer, TracerStorage storage,
                     TracerBreakpoints bpManager, String outputDir) {
        this.tracer     = tracer;
        this.storage    = storage;
        this.bpManager  = bpManager;
        this.outputDir  = outputDir;
    }

    // -------------------------------------------------------------------------
    // Main entry point — called by the /sink/run HTTP handler.
    // -------------------------------------------------------------------------

    public SinkResponse run(SinkRequest req) {
        long start = System.currentTimeMillis();

        // --- 0. Validate input --------------------------------------------------
        if (req.project == null || req.project.isBlank()) {
            return SinkResponse.error("project is required");
        }
        if (req.mainClass == null || req.mainClass.isBlank()) {
            return SinkResponse.error("mainClass is required");
        }

        // --- 1. Set requested breakpoints (additive — does not clear existing) --
        int bpSet = 0;
        if (req.breakpoints != null && !req.breakpoints.isEmpty()) {
            bpSet = setBreakpoints(req.breakpoints);
            System.out.println("[TraceSink] set " + bpSet + " breakpoints");
        }

        // --- 2. Launch debug session --------------------------------------------
        String launchResult;
        try {
            launchResult = tracer.launchDebug(req.project, req.mainClass);
        } catch (Exception e) {
            return SinkResponse.error("launch failed: " + e.getMessage());
        }
        if (launchResult.contains("error")) {
            return SinkResponse.error("launch error: " + launchResult);
        }

        // --- 3. Wait for first SUSPEND ------------------------------------------
        long deadline = System.currentTimeMillis() + req.timeoutMs;
        while (!tracer.isSuspended() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return SinkResponse.error("interrupted while waiting for suspend");
            }
        }
        if (!tracer.isSuspended()) {
            if (req.terminateAfter) silentTerminate();
            return SinkResponse.timeout(System.currentTimeMillis() - start);
        }

        // --- 4. Start recording -------------------------------------------------
        tracer.startRecording();

        // --- 5. Auto-step -------------------------------------------------------
        tracer.setAutoStepType(req.stepType);
        tracer.startAutoStep(req.maxSteps);

        // Wait for auto-step to finish (either maxSteps reached or process ends)
        while (tracer.isAutoStepping() && System.currentTimeMillis() < deadline + req.timeoutMs) {
            try { Thread.sleep(200); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // --- 6. Stop recording --------------------------------------------------
        List<StepEntry> entries = tracer.stopRecording();

        // --- 7. Save JSON -------------------------------------------------------
        String jsonPath = "";
        if (req.saveJson && !entries.isEmpty()) {
            jsonPath = saveJson(entries, req.project, req.mainClass);
        }

        // --- 8. Persist to SQLite (if storage available) -----------------------
        String sessionId = "";
        if (storage != null && storage.isOpen()) {
            sessionId = storage.getLastSessionId();
            if (sessionId == null) sessionId = "";
        }

        // --- 9. Terminate (optional) -------------------------------------------
        if (req.terminateAfter) silentTerminate();

        // --- 10. Build response -------------------------------------------------
        long duration = System.currentTimeMillis() - start;
        SinkResponse resp = new SinkResponse();
        resp.status     = SinkResponse.Status.OK;
        resp.sessionId  = sessionId;
        resp.stepCount  = entries.size();
        resp.steps      = entries;
        resp.jsonPath   = jsonPath;
        resp.durationMs = duration;
        return resp;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sets breakpoints from the request list.
     * Format: "ClassName:lineNumber", e.g. "com.example.Foo:42"
     */
    private int setBreakpoints(List<String> specs) {
        int count = 0;
        for (String spec : specs) {
            try {
                int colon = spec.lastIndexOf(':');
                if (colon < 0) continue;
                String typeName = spec.substring(0, colon).trim();
                int    line     = Integer.parseInt(spec.substring(colon + 1).trim());
                // Delegate to TracerBreakpoints which already handles JDIDebugModel via reflection
                boolean ok = bpManager.setLineBreakpoint(typeName, line);
                if (ok) count++;
            } catch (Exception e) {
                System.err.println("[TraceSink] could not set bp '" + spec + "': " + e.getMessage());
            }
        }
        return count;
    }

    /** Writes the step list as a JSON array to a file named after project+timestamp. */
    private String saveJson(List<StepEntry> entries, String project, String mainClass) {
        String ts   = String.valueOf(System.currentTimeMillis());
        String name = "sink-" + sanitize(project) + "-" + sanitize(mainClass) + "-" + ts + ".json";
        File   dir  = new File(outputDir);
        if (!dir.exists()) dir.mkdirs();
        File   out  = new File(dir, name);
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            pw.print("[");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) pw.print(",");
                pw.print(entries.get(i).toJson());
            }
            pw.print("]");
            System.out.println("[TraceSink] saved " + entries.size() + " steps → " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (IOException e) {
            System.err.println("[TraceSink] JSON write error: " + e.getMessage());
            return "";
        }
    }

    private void silentTerminate() {
        try {
            String result = tracer.debugTerminate();
            System.out.println("[TraceSink] terminate: " + result);
        } catch (Exception e) {
            System.err.println("[TraceSink] terminate error: " + e.getMessage());
        }
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }
}
