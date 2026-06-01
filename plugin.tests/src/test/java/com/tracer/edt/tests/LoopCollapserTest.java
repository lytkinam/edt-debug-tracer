package com.tracer.edt.tests;

import com.tracer.edt.core.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for LoopCollapser. No Eclipse runtime required.
 */
public class LoopCollapserTest {

    private static TraceEntry entry(String proc, int line) {
        return new TraceEntry(proc, line, "main", 1);
    }

    @Test
    public void testNoDuplication() {
        List<TraceEntry> raw = List.of(entry("ProcA", 1), entry("ProcB", 2), entry("ProcC", 3));
        List<CollapsedTraceEntry> result = new LoopCollapser().collapse(raw);
        assertEquals(3, result.size());
    }

    @Test
    public void testConsecutiveDuplicatesCollapsed() {
        List<TraceEntry> raw = new ArrayList<>();
        for (int i = 0; i < 5; i++) raw.add(entry("ProcA", 10));
        raw.add(entry("ProcB", 20));
        List<CollapsedTraceEntry> result = new LoopCollapser().collapse(raw);
        assertEquals(2, result.size());
        assertTrue(result.get(0).toJson().contains("\"kind\":\"repeat\""));
        assertTrue(result.get(0).toJson().contains("\"repeat_count\":5"));
    }

    @Test
    public void testLoopPatternCollapsed() {
        List<TraceEntry> raw = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            raw.add(entry("ProcA", 1));
            raw.add(entry("ProcB", 2));
            raw.add(entry("ProcC", 3));
        }
        List<CollapsedTraceEntry> result = new LoopCollapser().collapse(raw);
        assertTrue("Loop should compress: got " + result.size(), result.size() < 9);
    }

    @Test
    public void testEmptyInput() {
        List<CollapsedTraceEntry> result = new LoopCollapser().collapse(new ArrayList<>());
        assertTrue(result.isEmpty());
    }
}
