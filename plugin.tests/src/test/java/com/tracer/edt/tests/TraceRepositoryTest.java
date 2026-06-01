package com.tracer.edt.tests;

import com.tracer.edt.core.*;
import com.tracer.edt.db.TraceRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.List;

/**
 * Integration test for TraceRepository against a temp SQLite file.
 * Requires sqlite-jdbc.jar on classpath.
 */
public class TraceRepositoryTest {

    private Path tmpDb;
    private TraceRepository repo;

    @Before
    public void setUp() throws Exception {
        tmpDb = Files.createTempFile("edt-trace-test-", ".db");
        repo = new TraceRepository(tmpDb);
        repo.init();
    }

    @After
    public void tearDown() throws Exception {
        try { if (repo != null) repo.close(); } finally { Files.deleteIfExists(tmpDb); }
    }

    @Test
    public void testStartAndStopSession() throws SQLException {
        repo.startSession("s1");
        repo.stopSession("s1");
    }

    @Test
    public void testInsertRawAndLoad() throws SQLException {
        repo.startSession("s2");
        AsyncTraceWriter.QueuedTraceEntry q = new AsyncTraceWriter.QueuedTraceEntry(
            "s2", new TraceEntry("Procedure1", 42, "main", 2)
        );
        repo.insertRawBatch(List.of(q));
        List<TraceEntry> entries = repo.loadRaw("s2");
        assertEquals(1, entries.size());
        assertEquals("Procedure1", entries.get(0).getProcedure());
        assertEquals(42, entries.get(0).getLine());
    }

    @Test
    public void testPostprocessPersistsClean() throws SQLException {
        repo.startSession("s3");
        AsyncTraceWriter.QueuedTraceEntry q = new AsyncTraceWriter.QueuedTraceEntry(
            "s3", new TraceEntry("Цикл", 10, "main", 1)
        );
        repo.insertRawBatch(List.of(q, q, q, q));
        List<TraceEntry> raw = repo.loadRaw("s3");
        List<CollapsedTraceEntry> clean = new LoopCollapser().collapse(raw);
        repo.replaceClean("s3", clean);
        String json = repo.traceAsJson("s3", "clean");
        assertTrue(json.contains("Цикл"));
    }

    @Test
    public void testTraceJsonRaw() throws SQLException {
        repo.startSession("s4");
        AsyncTraceWriter.QueuedTraceEntry q = new AsyncTraceWriter.QueuedTraceEntry(
            "s4", new TraceEntry("ПроверкаJSON", 5, "main", 1)
        );
        repo.insertRawBatch(List.of(q));
        String json = repo.traceAsJson("s4", "raw");
        assertTrue(json.startsWith("["));
        assertTrue(json.contains("ПроверкаJSON"));
    }
}
