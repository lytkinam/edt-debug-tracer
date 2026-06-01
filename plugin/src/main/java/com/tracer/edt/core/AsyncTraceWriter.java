package com.tracer.edt.core;

import com.tracer.edt.db.TraceRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drains trace entries from a queue and writes them to SQLite in background.
 * Never blocks the Eclipse Debug event thread.
 */
public class AsyncTraceWriter implements Runnable {

    private static final Logger LOG = Logger.getLogger(AsyncTraceWriter.class.getName());
    private static final int BATCH_SIZE = 50;
    private static final int DRAIN_TIMEOUT_MS = 100;

    private final BlockingQueue<QueuedTraceEntry> queue = new LinkedBlockingQueue<>(10_000);
    private final TraceRepository repo;
    private volatile boolean running = false;
    private Thread thread;

    public AsyncTraceWriter(TraceRepository repo) {
        this.repo = repo;
    }

    public void start() {
        running = true;
        thread = new Thread(this, "edt-trace-writer");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    public void enqueue(String sessionId, TraceEntry entry) {
        if (!queue.offer(new QueuedTraceEntry(sessionId, entry))) {
            LOG.warning("Trace queue full, dropping entry: " + entry);
        }
    }

    @Override
    public void run() {
        List<QueuedTraceEntry> batch = new ArrayList<>(BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                QueuedTraceEntry head = queue.poll(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (head != null) {
                    batch.add(head);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                    repo.insertRawBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (SQLException e) {
                LOG.log(Level.SEVERE, "Failed to write trace batch", e);
            }
        }
    }

    /** Visible for tests */
    public static class QueuedTraceEntry {
        public final String sessionId;
        public final TraceEntry entry;
        public QueuedTraceEntry(String sessionId, TraceEntry entry) {
            this.sessionId = sessionId;
            this.entry = entry;
        }
    }
}
