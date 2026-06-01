package com.tracer.edt.debug;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IThread;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Dedicated IDebugEventSetListener for event-driven wait_for_break.
 * Separate from DebugTracerListener (which records trace to SQLite).
 * Registered with DebugPlugin on first waitForBreak() call (lazy).
 */
public class WaitForBreakListener implements IDebugEventSetListener {

    private static final Logger LOG = Logger.getLogger(WaitForBreakListener.class.getName());

    private volatile CompletableFuture<IThread> pendingFuture;

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        CompletableFuture<IThread> future = this.pendingFuture;
        if (future == null || future.isDone()) return;

        for (DebugEvent event : events) {
            if (event.getKind() == DebugEvent.SUSPEND && event.getSource() instanceof IThread thread) {
                if (!future.isDone()) {
                    future.complete(thread);
                    return;
                }
            }
        }
    }

    /**
     * Wait for the next SUSPEND event with timeout.
     * @param timeoutMs timeout in milliseconds
     * @return the suspended thread, or null on timeout
     */
    public IThread waitForSuspend(long timeoutMs) {
        CompletableFuture<IThread> future = new CompletableFuture<>();
        this.pendingFuture = future;
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            LOG.warning("waitForSuspend interrupted: " + e.getMessage());
            return null;
        } finally {
            this.pendingFuture = null;
        }
    }

    /** Check if a wait is currently pending. */
    public boolean isPending() {
        CompletableFuture<IThread> f = this.pendingFuture;
        return f != null && !f.isDone();
    }
}
