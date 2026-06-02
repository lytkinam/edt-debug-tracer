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

    // State: parentSeq tracking (1.5)
    private int previousDepth = -1;
    private int currentParentSeq = -1;
    private final int[] lastStepAtDepth = new int[256];
    private int currentSeq = 0;

    public void setOutputPath(String path) { this.outputPath = path; }

    public void setConfig(Properties props) {
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
    }

    public void startRecording() {
        entries.clear();
        queue.clear();
        totalSteps = 0;
        // Reset parentSeq tracking state (1.5)
        previousDepth = -1;
        currentParentSeq = -1;
        currentSeq = 0;
        for (int i = 0; i < 256; i++) lastStepAtDepth[i] = -1;
        recording.set(true);
        startWriter();
    }

    public List<StepEntry> stopRecording() {
        recording.set(false);
        autoStepping.set(false);
        stepsRemaining.set(0);
        stopWriter();
        return List.copyOf(entries);
    }

    public void startAutoStep(int maxSteps) {
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
        if (autoStepping.get() && stepsRemaining.getAndDecrement() > 0) {
            try {
                thread.stepOver();
            } catch (DebugException e) {
                autoStepping.set(false);
            }
        } else if (autoStepping.get()) {
            autoStepping.set(false);
            System.out.println("[tracer] auto-step complete, total=" + totalSteps);
        }
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

                // Offload to writer queue — non-blocking
                queue.offer(entry);

            } catch (Exception e) { /* skip */ }

            // Step immediately — no thread creation overhead
            if (autoStepping.get()) {
                doAutoStep(thread);
            }
        }
    }
}
