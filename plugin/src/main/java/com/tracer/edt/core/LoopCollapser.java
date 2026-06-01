package com.tracer.edt.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-processes raw trace: collapses consecutive identical steps and detects repeating patterns.
 */
public class LoopCollapser {

    private static final int MIN_REPEAT = 2;
    private static final int MAX_PATTERN = 20;

    public List<CollapsedTraceEntry> collapse(List<TraceEntry> raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<>();

        List<CollapsedTraceEntry> result = new ArrayList<>();
        int i = 0;
        while (i < raw.size()) {
            // 1. Try to find a repeating pattern of length 2..MAX_PATTERN
            int patternMatch = findPattern(raw, i);
            if (patternMatch > 0) {
                int patternLen = patternMatch;
                int repeatCount = 1;
                int j = i + patternLen;
                while (j + patternLen <= raw.size() && matchesPattern(raw, i, j, patternLen)) {
                    repeatCount++;
                    j += patternLen;
                }
                if (repeatCount >= MIN_REPEAT) {
                    TraceEntry first = raw.get(i);
                    result.add(new CollapsedTraceEntry(
                        CollapsedTraceEntry.Kind.LOOP, first.getProcedure(), first.getLine(),
                        first.getModule(), repeatCount, patternLen, first.getTs()));
                    i = j;
                    continue;
                }
            }

            // 2. Collapse consecutive identical single steps
            TraceEntry cur = raw.get(i);
            int count = 1;
            while (i + count < raw.size() && sameStep(cur, raw.get(i + count))) count++;
            if (count > 1) {
                result.add(new CollapsedTraceEntry(
                    CollapsedTraceEntry.Kind.REPEAT, cur.getProcedure(), cur.getLine(),
                    cur.getModule(), count, 1, cur.getTs()));
                i += count;
            } else {
                result.add(new CollapsedTraceEntry(
                    CollapsedTraceEntry.Kind.STEP, cur.getProcedure(), cur.getLine(),
                    cur.getModule(), 1, 1, cur.getTs()));
                i++;
            }
        }
        return result;
    }

    private int findPattern(List<TraceEntry> raw, int start) {
        for (int len = 2; len <= MAX_PATTERN && start + len * 2 <= raw.size(); len++) {
            if (matchesPattern(raw, start, start + len, len)) return len;
        }
        return 0;
    }

    private boolean matchesPattern(List<TraceEntry> raw, int a, int b, int len) {
        if (b + len > raw.size()) return false;
        for (int k = 0; k < len; k++) {
            if (!sameStep(raw.get(a + k), raw.get(b + k))) return false;
        }
        return true;
    }

    private boolean sameStep(TraceEntry a, TraceEntry b) {
        return a.getLine() == b.getLine()
            && java.util.Objects.equals(a.getProcedure(), b.getProcedure())
            && java.util.Objects.equals(a.getModule(), b.getModule());
    }
}
