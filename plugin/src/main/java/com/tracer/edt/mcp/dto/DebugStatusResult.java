package com.tracer.edt.mcp.dto;

public record DebugStatusResult(
    String projectName, String state, boolean suspended,
    int launchCount, int targetCount, int breakpointCount,
    String activeThreadId, String message
) {}
