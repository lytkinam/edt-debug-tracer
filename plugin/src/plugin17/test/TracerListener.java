package plugin17.test;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IDebugTarget;
import java.io.FileWriter;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
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

    public void setOutputPath(String path) { this.outputPath = path; }

    public void startRecording() {
        entries.clear();
        queue.clear();
        totalSteps = 0;
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

                // Capture raw data — fast
                StepEntry entry = new StepEntry(
                    frame.getName(), frame.getLineNumber(), "",
                    System.identityHashCode(thread), System.currentTimeMillis());
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
