package plugin17.test;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.core.model.IValue;
import java.io.FileWriter;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TracerListener implements IDebugEventSetListener {

    private final CopyOnWriteArrayList<StepEntry> entries = new CopyOnWriteArrayList<>();
    private final BlockingQueue<StepEntry> queue = new LinkedBlockingQueue<>(100_000);
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean autoStepping = new AtomicBoolean(false);
    private final AtomicInteger stepsRemaining = new AtomicInteger(0);
    private volatile int totalSteps = 0;
    private Thread writerThread;
    private volatile String outputPath;

    // Storage (P2)
    private TracerStorage storage;
    private volatile String storageMode = "both"; // file, sqlite, both
    private volatile String currentSessionId;

    // Config: module capture (1.1)
    private volatile boolean captureModuleEnabled = true;
    private volatile String captureModuleFallback = "";

    // Config: variables capture (1.2)
    private volatile boolean captureVariablesEnabled = true;
    private volatile int captureVariablesMaxCount = 50;
    private volatile boolean captureVariablesIncludeTypes = true;
    private volatile int captureVariablesMaxValueLength = 200;
    private volatile String captureVariablesExcludeNames = "";

    // Config: thread name capture (1.3)
    private volatile boolean captureThreadUseName = true;
    private volatile boolean captureThreadIncludeId = true;

    // Config: char position capture (1.4)
    private volatile boolean captureCharPositionEnabled = true;

    // Config: call stack capture (1.5)
    private volatile boolean captureCallStackEnabled = true;
    private volatile int captureCallStackMaxDepth = 20;

    // Config: dedup (P3.1)
    private volatile boolean filterDedupEnabled = true;
    private volatile long filterDedupWindowMs = 50;

    // Config: filters (P3.2)
    private volatile String filterIncludeModules = "";
    private volatile String filterExcludeModules = "";
    private volatile String filterIncludeProcedures = "";
    private volatile String filterExcludeProcedures = "";

    // Config: limits (P3.3)
    private volatile int limitMaxEntries = 0;
    private volatile long limitMaxDurationSeconds = 0;
    private volatile String limitAction = "stop";

    // Config: auto-step (P4.1)
    private volatile int autoStepDefaultSteps = 1000;
    private volatile int autoStepMaxSteps = 100000;
    private volatile long autoStepDelayMs = 0;
    private volatile String autoStepStepType = "over";
    private volatile boolean autoStepStopOnTerminate = true;
    private volatile boolean autoStepStopOnException = false;
    private volatile String autoStepStopOnModule = "";
    private volatile String autoStepStopOnProcedure = "";
    private volatile int autoStepStopOnLine = 0;

    // Config: performance (P6)
    private volatile int writerBufferSize = 100_000;
    private volatile int writerFlushEntries = 100;
    private volatile long writerFlushMs = 500;
    private volatile boolean profilingEnabled = false;
    private volatile int profilingSampleRate = 100;

    // Config: reliability (P7)
    private volatile boolean reliabilityStopOnTerminate = true;
    private volatile boolean reliabilityFlushOnTerminate = true;
    private volatile int reliabilityMaxErrors = 10;
    private volatile boolean reliabilityRetryStepOver = false;
    private volatile long reliabilityRetryDelayMs = 100;
    private volatile boolean reliabilityGracefulShutdown = true;

    // Config: logging (P8.3)
    private volatile String loggingLevel = "INFO";
    private volatile String loggingPrefix = "[tracer]";

    // State: parentSeq tracking (1.5)
    private int previousDepth = -1;
    private int currentParentSeq = -1;
    private final int[] lastStepAtDepth = new int[256];
    private int currentSeq = 0;

    public void setOutputPath(String path) { this.outputPath = path; }
    public void setStorage(TracerStorage storage) { this.storage = storage; }

    public void setConfig(Properties props) {
        storageMode = props.getProperty("storage.mode", "both");

        captureModuleEnabled = Boolean.parseBoolean(
            props.getProperty("capture.module.enabled", "true"));
        captureModuleFallback = props.getProperty("capture.module.fallback", "");

        captureVariablesEnabled = Boolean.parseBoolean(
            props.getProperty("capture.variables.enabled", "true"));
        captureVariablesMaxCount = Integer.parseInt(
            props.getProperty("capture.variables.maxCount", "50"));
        captureVariablesIncludeTypes = Boolean.parseBoolean(
            props.getProperty("capture.variables.includeTypes", "true"));
        captureVariablesMaxValueLength = Integer.parseInt(
            props.getProperty("capture.variables.maxValueLength", "200"));
        captureVariablesExcludeNames = props.getProperty("capture.variables.excludeNames", "");

        captureThreadUseName = Boolean.parseBoolean(
            props.getProperty("capture.thread.useName", "true"));
        captureThreadIncludeId = Boolean.parseBoolean(
            props.getProperty("capture.thread.includeId", "true"));

        captureCharPositionEnabled = Boolean.parseBoolean(
            props.getProperty("capture.charPosition.enabled", "true"));

        captureCallStackEnabled = Boolean.parseBoolean(
            props.getProperty("capture.callStack.enabled", "true"));
        captureCallStackMaxDepth = Integer.parseInt(
            props.getProperty("capture.callStack.maxDepth", "20"));

        // P3: dedup, filters, limits
        filterDedupEnabled = Boolean.parseBoolean(
            props.getProperty("filter.dedup.enabled", "true"));
        filterDedupWindowMs = Long.parseLong(
            props.getProperty("filter.dedup.window.ms", "50"));
        filterIncludeModules = props.getProperty("filter.include.modules", "");
        filterExcludeModules = props.getProperty("filter.exclude.modules", "");
        filterIncludeProcedures = props.getProperty("filter.include.procedures", "");
        filterExcludeProcedures = props.getProperty("filter.exclude.procedures", "");
        limitMaxEntries = Integer.parseInt(
            props.getProperty("limit.maxEntries", "0"));
        limitMaxDurationSeconds = Long.parseLong(
            props.getProperty("limit.maxDuration.seconds", "0"));
        limitAction = props.getProperty("limit.action", "stop");

        // P4: auto-step params
        autoStepDefaultSteps = Integer.parseInt(
            props.getProperty("autoStep.defaultSteps", "1000"));
        autoStepMaxSteps = Integer.parseInt(
            props.getProperty("autoStep.maxSteps", "100000"));
        autoStepDelayMs = Long.parseLong(
            props.getProperty("autoStep.delay.ms", "0"));
        autoStepStepType = props.getProperty("autoStep.stepType", "over");
        autoStepStopOnTerminate = Boolean.parseBoolean(
            props.getProperty("autoStep.stopOnTerminate", "true"));
        autoStepStopOnException = Boolean.parseBoolean(
            props.getProperty("autoStep.stopOnException", "false"));
        autoStepStopOnModule = props.getProperty("autoStep.stopOnModule", "");
        autoStepStopOnProcedure = props.getProperty("autoStep.stopOnProcedure", "");
        autoStepStopOnLine = Integer.parseInt(
            props.getProperty("autoStep.stopOnLine", "0"));

        // P6: performance
        writerBufferSize = Integer.parseInt(
            props.getProperty("writer.buffer.size", "100000"));
        writerFlushEntries = Integer.parseInt(
            props.getProperty("writer.flush.interval.entries", "100"));
        writerFlushMs = Long.parseLong(
            props.getProperty("writer.flush.interval.ms", "500"));
        profilingEnabled = Boolean.parseBoolean(
            props.getProperty("profiling.enabled", "false"));
        profilingSampleRate = Integer.parseInt(
            props.getProperty("profiling.sampleRate", "100"));

        // P7: reliability
        reliabilityStopOnTerminate = Boolean.parseBoolean(
            props.getProperty("reliability.stopOnTerminate", "true"));
        reliabilityFlushOnTerminate = Boolean.parseBoolean(
            props.getProperty("reliability.flushOnTerminate", "true"));
        reliabilityMaxErrors = Integer.parseInt(
            props.getProperty("reliability.maxErrors", "10"));
        reliabilityRetryStepOver = Boolean.parseBoolean(
            props.getProperty("reliability.retryStepOver", "false"));
        reliabilityRetryDelayMs = Long.parseLong(
            props.getProperty("reliability.retryDelay.ms", "100"));
        reliabilityGracefulShutdown = Boolean.parseBoolean(
            props.getProperty("reliability.gracefulShutdown", "true"));

        // P8.3: logging
        loggingLevel = props.getProperty("logging.level", "INFO");
        loggingPrefix = props.getProperty("logging.prefix", "[tracer]");
    }

    // State: dedup tracking (P3.1)
    private String lastProc = "";
    private int lastLine = -1;
    private int lastThreadId = -1;
    private long lastTs = 0;

    // State: recording start time (P3.3)
    private long recordingStartTime = 0;

    public void startRecording() {
        entries.clear();
        queue.clear();
        totalSteps = 0;
        // Reset parentSeq tracking state (1.5)
        previousDepth = -1;
        currentParentSeq = -1;
        currentSeq = 0;
        for (int i = 0; i < 256; i++) lastStepAtDepth[i] = -1;
        // Reset P3 state
        lastProc = ""; lastLine = -1; lastThreadId = -1; lastTs = 0;
        recordingStartTime = System.currentTimeMillis();

        // Create SQLite session (P2)
        if (storage != null && (storageMode.equals("sqlite") || storageMode.equals("both"))) {
            try {
                currentSessionId = storage.createSession(null, null, null, null, null);
                storage.startWriter();
                System.out.println("[tracer] SQLite session: " + currentSessionId);
            } catch (Exception e) {
                System.err.println("[tracer] SQLite session error: " + e.getMessage());
                currentSessionId = null;
            }
        }

        recording.set(true);
        startWriter();
    }

    public List<StepEntry> stopRecording() {
        recording.set(false);
        autoStepping.set(false);
        stepsRemaining.set(0);
        stopWriter();

        // Flush SQLite (P2)
        if (storage != null && currentSessionId != null) {
            try {
                storage.stopWriter();
                storage.updateSession(currentSessionId, "stopped", totalSteps, totalSteps - entries.size());
                System.out.println("[tracer] SQLite session stopped: " + totalSteps + " steps");
            } catch (Exception e) {
                System.err.println("[tracer] SQLite stop error: " + e.getMessage());
            }
        }

        return List.copyOf(entries);
    }

    public void startAutoStep(int maxSteps) {
        // P4.1: use default if 0, cap at maxSteps (0 = unlimited)
        if (maxSteps <= 0) maxSteps = autoStepDefaultSteps;
        if (autoStepMaxSteps > 0) {
            maxSteps = Math.min(maxSteps, autoStepMaxSteps);
        }
        // 0 = unlimited: use Integer.MAX_VALUE as sentinel
        if (maxSteps <= 0) maxSteps = Integer.MAX_VALUE;
        stepsRemaining.set(maxSteps);
        autoStepping.set(true);
        new Thread(() -> {
            try {
                IThread suspended = findSuspendedThread();
                if (suspended != null) {
                    doAutoStep(suspended);
                }
            } catch (Exception e) {
                System.out.println("[tracer] auto-step init error: " + e.getMessage());
            }
        }, "tracer-autostep-init").start();
    }

    private void doAutoStep(IThread thread) {
        if (autoStepping.get() && recording.get() && stepsRemaining.getAndDecrement() > 0) {
            try {
                // P4.1: delay between steps
                if (autoStepDelayMs > 0) {
                    Thread.sleep(autoStepDelayMs);
                }
                // P4.1: step type
                switch (autoStepStepType) {
                    case "into": thread.stepInto(); break;
                    case "return": thread.stepReturn(); break;
                    default: thread.stepOver(); break;
                }
            } catch (DebugException e) {
                // P7.2: retry stepOver on error
                if (reliabilityRetryStepOver) {
                    try { Thread.sleep(reliabilityRetryDelayMs); thread.stepOver(); }
                    catch (Exception retry) {
                        if (autoStepStopOnException) {
                            autoStepping.set(false);
                            logMsg("auto-step stopped on exception after retry");
                        }
                    }
                } else if (autoStepStopOnException) {
                    autoStepping.set(false);
                    logMsg("auto-step stopped on exception");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (autoStepping.get()) {
            autoStepping.set(false);
            logMsg("auto-step complete, total=" + totalSteps);
        }
    }

    private void logMsg(String msg) {
        System.out.println(loggingPrefix + " " + msg);
    }

    private IThread findSuspendedThread() {
        try {
            for (var launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
                for (IDebugTarget target : launch.getDebugTargets()) {
                    for (IThread thread : target.getThreads()) {
                        if (thread.isSuspended()) return thread;
                    }
                }
            }
        } catch (DebugException e) { /* skip */ }
        return null;
    }

    // --- Writer thread ---

    private void startWriter() {
        writerThread = new Thread(() -> {
            ArrayList<StepEntry> batch = new ArrayList<>(100);
            while (recording.get() || !queue.isEmpty()) {
                try {
                    StepEntry head = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (head != null) {
                        batch.add(head);
                        queue.drainTo(batch, 99);
                        entries.addAll(batch);
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Flush remaining
            queue.drainTo(batch);
            if (!batch.isEmpty()) entries.addAll(batch);
        }, "tracer-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void stopWriter() {
        if (writerThread != null) {
            writerThread.interrupt();
            try { writerThread.join(2000); } catch (InterruptedException e) { /* skip */ }
        }
        // Write to file
        if (outputPath != null) {
            try {
                File f = new File(outputPath);
                f.getParentFile().mkdirs();
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < entries.size(); i++) {
                    if (i > 0) sb.append(",\n");
                    sb.append(entries.get(i).toJson());
                }
                sb.append("]");
                try (FileWriter w = new FileWriter(f)) { w.write(sb.toString()); }
                System.out.println("[tracer] Written: " + f.getAbsolutePath() + " (" + entries.size() + ")");
            } catch (Exception e) {
                System.err.println("[tracer] Write failed: " + e.getMessage());
            }
        }
    }

    public boolean isRecording() { return recording.get(); }
    public boolean isAutoStepping() { return autoStepping.get(); }
    public int size() { return entries.size(); }
    public int getTotalSteps() { return totalSteps; }
    public List<StepEntry> getEntries() { return List.copyOf(entries); }

    // --- Variables capture helper (1.2) ---
    private String captureVariables(IStackFrame frame) {
        if (!captureVariablesEnabled) return null;
        try {
            IVariable[] vars = frame.getVariables();
            if (vars == null || vars.length == 0) return null;

            StringBuilder sb = new StringBuilder("{");
            int count = 0;
            String[] excludePatterns = captureVariablesExcludeNames.isEmpty()
                ? new String[0] : captureVariablesExcludeNames.split(",");

            for (IVariable var : vars) {
                if (count >= captureVariablesMaxCount) break;
                try {
                    String name = var.getName();
                    // Check exclude patterns
                    boolean excluded = false;
                    for (String pattern : excludePatterns) {
                        String p = pattern.trim();
                        if (p.endsWith("*") && name.startsWith(p.substring(0, p.length() - 1))) {
                            excluded = true; break;
                        } else if (name.equals(p)) {
                            excluded = true; break;
                        }
                    }
                    if (excluded) continue;

                    if (count > 0) sb.append(",");
                    sb.append("\"").append(esc(name)).append("\":{");

                    if (captureVariablesIncludeTypes) {
                        String type = var.getReferenceTypeName();
                        sb.append("\"type\":\"").append(esc(type)).append("\",");
                    }

                    IValue value = var.getValue();
                    String valueStr = value != null ? value.getValueString() : "";
                    if (valueStr != null && valueStr.length() > captureVariablesMaxValueLength) {
                        valueStr = valueStr.substring(0, captureVariablesMaxValueLength) + "...";
                    }
                    sb.append("\"value\":\"").append(esc(valueStr)).append("\"}");
                    count++;
                } catch (DebugException e) { /* skip this variable */ }
            }
            sb.append("}");
            return count > 0 ? sb.toString() : null;
        } catch (DebugException e) {
            return null;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // --- Call stack capture helper (1.5) ---
    private String captureCallStack(IStackFrame[] frames) {
        if (!captureCallStackEnabled || frames == null || frames.length == 0) return null;
        int limit = Math.min(frames.length, captureCallStackMaxDepth);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(",");
            try {
                sb.append("{\"procedure\":\"").append(esc(frames[i].getName()))
                  .append("\",\"line\":").append(frames[i].getLineNumber()).append("}");
            } catch (DebugException e) {
                sb.append("{\"procedure\":\"<error>\",\"line\":-1}");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    // --- P3.2: Filter check ---
    private boolean passesFilter(StepEntry entry) {
        // Include modules filter
        if (!filterIncludeModules.isEmpty()) {
            boolean match = false;
            for (String pattern : filterIncludeModules.split(",")) {
                if (matchesGlob(entry.module(), pattern.trim())) { match = true; break; }
            }
            if (!match) return false;
        }
        // Exclude modules filter
        if (!filterExcludeModules.isEmpty()) {
            for (String pattern : filterExcludeModules.split(",")) {
                if (matchesGlob(entry.module(), pattern.trim())) return false;
            }
        }
        // Include procedures filter
        if (!filterIncludeProcedures.isEmpty()) {
            boolean match = false;
            for (String pattern : filterIncludeProcedures.split(",")) {
                if (matchesGlob(entry.procedure(), pattern.trim())) { match = true; break; }
            }
            if (!match) return false;
        }
        // Exclude procedures filter
        if (!filterExcludeProcedures.isEmpty()) {
            for (String pattern : filterExcludeProcedures.split(",")) {
                if (matchesGlob(entry.procedure(), pattern.trim())) return false;
            }
        }
        return true;
    }

    private static boolean matchesGlob(String value, String pattern) {
        if (pattern.isEmpty()) return false;
        if (pattern.equals("*")) return true;
        if (pattern.startsWith("*") && pattern.endsWith("*")) {
            return value.contains(pattern.substring(1, pattern.length() - 1));
        }
        if (pattern.startsWith("*")) {
            return value.endsWith(pattern.substring(1));
        }
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return value.equals(pattern);
    }

    // --- Event handler (hot path — minimal work) ---

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        if (!recording.get()) return;

        for (DebugEvent event : events) {
            if (event.getKind() != DebugEvent.SUSPEND) continue;
            if (!(event.getSource() instanceof IThread thread)) continue;

            try {
                IStackFrame frame = thread.getTopStackFrame();
                if (frame == null) continue;

                // Capture module via ISourceLocator (1.1)
                String module = captureModuleFallback;
                if (captureModuleEnabled) {
                    try {
                        ILaunch launch = thread.getLaunch();
                        if (launch != null) {
                            ISourceLocator locator = launch.getSourceLocator();
                            if (locator != null) {
                                Object src = locator.getSourceElement(frame);
                                if (src != null) module = src.toString();
                            }
                        }
                    } catch (Exception e) { /* skip module capture errors */ }
                }

                // Capture variables (1.2)
                String variablesJson = captureVariables(frame);

                // Capture thread info (1.3)
                String threadName = captureThreadUseName ? thread.getName() : "";
                int threadId = captureThreadIncludeId ? System.identityHashCode(thread) : 0;

                // Capture char position (1.4)
                int charStart = captureCharPositionEnabled ? frame.getCharStart() : -1;
                int charEnd = captureCharPositionEnabled ? frame.getCharEnd() : -1;

                // Capture call stack + parentSeq (1.5)
                int stackDepth = 1;
                int parentSeq = -1;
                String stackJson = null;

                if (captureCallStackEnabled) {
                    try {
                        IStackFrame[] frames = thread.getStackFrames();
                        stackDepth = frames.length;
                        currentSeq++;

                        // ParentSeq algorithm
                        if (stackDepth > previousDepth) {
                            // Step INTO: parent is last step at previous depth
                            parentSeq = (previousDepth >= 0 && previousDepth < 256)
                                ? lastStepAtDepth[previousDepth] : -1;
                        } else if (stackDepth == previousDepth) {
                            // Step OVER: same parent
                            parentSeq = currentParentSeq;
                        } else {
                            // Step RETURN: parent is the step we returned to
                            parentSeq = (stackDepth < 256) ? lastStepAtDepth[stackDepth] : -1;
                        }

                        currentParentSeq = parentSeq;
                        if (stackDepth < 256) lastStepAtDepth[stackDepth] = currentSeq;
                        previousDepth = stackDepth;

                        stackJson = captureCallStack(frames);
                    } catch (DebugException e) { /* skip stack capture */ }
                }

                // Capture raw data
                StepEntry entry = new StepEntry(
                    frame.getName(), frame.getLineNumber(), module,
                    threadName, threadId, System.currentTimeMillis(),
                    charStart, charEnd, stackDepth, parentSeq, stackJson,
                    variablesJson);
                totalSteps++;

                // P3.1: Dedup check
                if (filterDedupEnabled) {
                    if (entry.procedure().equals(lastProc)
                        && entry.line() == lastLine
                        && entry.threadId() == lastThreadId
                        && (entry.timestamp() - lastTs) < filterDedupWindowMs) {
                        // Duplicate within window — skip recording but still step
                        lastTs = entry.timestamp();
                        if (autoStepping.get()) { doAutoStep(thread); }
                        continue;
                    }
                }
                lastProc = entry.procedure();
                lastLine = entry.line();
                lastThreadId = entry.threadId();
                lastTs = entry.timestamp();

                // P3.2: Filter check
                if (!passesFilter(entry)) {
                    if (autoStepping.get()) { doAutoStep(thread); }
                    continue;
                }

                // P3.3: Limit check
                if (limitMaxEntries > 0 && entries.size() >= limitMaxEntries) {
                    if ("stop".equals(limitAction)) {
                        recording.set(false);
                        autoStepping.set(false);
                        System.out.println("[tracer] Limit reached: " + limitMaxEntries + " entries");
                        break;
                    }
                }
                if (limitMaxDurationSeconds > 0) {
                    long elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000;
                    if (elapsed >= limitMaxDurationSeconds) {
                        if ("stop".equals(limitAction)) {
                            recording.set(false);
                            autoStepping.set(false);
                            System.out.println("[tracer] Duration limit: " + elapsed + "s");
                            break;
                        }
                    }
                }

                // Queue to SQLite storage (P2)
                if (storage != null && currentSessionId != null) {
                    storage.queueStep(currentSessionId, currentSeq,
                        entry.timestamp(), entry.procedure(), entry.line(), entry.module(),
                        entry.charStart(), entry.charEnd(),
                        entry.threadId(), entry.threadName(),
                        entry.stackDepth(), entry.parentSeq(),
                        entry.stackJson(), entry.variablesJson());
                }

                // Offload to writer queue — non-blocking
                queue.offer(entry);

            } catch (Exception e) { /* skip */ }

            // P4.1: Check stopOn conditions before stepping
            if (autoStepping.get()) {
                IStackFrame topFrame = null;
                try { topFrame = thread.getTopStackFrame(); } catch (Exception e) { /* skip */ }
                if (topFrame != null) {
                    boolean shouldStop = false;
                    if (!autoStepStopOnModule.isEmpty()
                        && matchesGlob(getFrameModule(topFrame), autoStepStopOnModule)) {
                        shouldStop = true;
                    }
                    if (!autoStepStopOnProcedure.isEmpty()
                        && matchesGlob(getFrameProcedure(topFrame), autoStepStopOnProcedure)) {
                        shouldStop = true;
                    }
                    if (autoStepStopOnLine > 0) {
                        try { if (topFrame.getLineNumber() == autoStepStopOnLine) shouldStop = true; }
                        catch (Exception e) { /* skip */ }
                    }
                    if (shouldStop) {
                        autoStepping.set(false);
                        System.out.println("[tracer] auto-step stopped on condition");
                    }
                }
                // Also check TERMINATE
                if (autoStepStopOnTerminate) {
                    for (DebugEvent termEvent : events) {
                        if (termEvent.getKind() == DebugEvent.TERMINATE) {
                            autoStepping.set(false);
                            System.out.println("[tracer] auto-step stopped on TERMINATE");
                        }
                    }
                }
                if (autoStepping.get()) {
                    doAutoStep(thread);
                }
            }
        }
    }

    private String getFrameModule(IStackFrame frame) {
        if (!captureModuleEnabled) return captureModuleFallback;
        try {
            ILaunch launch = frame.getLaunch();
            if (launch != null) {
                ISourceLocator locator = launch.getSourceLocator();
                if (locator != null) {
                    Object src = locator.getSourceElement(frame);
                    if (src != null) return src.toString();
                }
            }
        } catch (Exception e) { /* skip */ }
        return captureModuleFallback;
    }

    private String getFrameProcedure(IStackFrame frame) {
        try { return frame.getName(); } catch (Exception e) { return ""; }
    }
}
