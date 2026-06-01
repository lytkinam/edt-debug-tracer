package plugin17.test;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class TracerListener implements IDebugEventSetListener {

    private final CopyOnWriteArrayList<StepEntry> entries = new CopyOnWriteArrayList<>();
    private final AtomicBoolean recording = new AtomicBoolean(false);

    public void startRecording() {
        entries.clear();
        recording.set(true);
    }

    public List<StepEntry> stopRecording() {
        recording.set(false);
        return List.copyOf(entries);
    }

    public boolean isRecording() { return recording.get(); }
    public int size() { return entries.size(); }

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        if (!recording.get()) return;
        for (DebugEvent event : events) {
            if (event.getKind() != DebugEvent.SUSPEND) continue;
            if (!(event.getSource() instanceof IThread thread)) continue;
            try {
                IStackFrame frame = thread.getTopStackFrame();
                if (frame == null) continue;
                entries.add(new StepEntry(
                    frame.getName(),
                    frame.getLineNumber(),
                    "",  // module - filled later
                    System.identityHashCode(thread),
                    System.currentTimeMillis()
                ));
            } catch (Exception e) {
                // skip
            }
        }
    }
}
