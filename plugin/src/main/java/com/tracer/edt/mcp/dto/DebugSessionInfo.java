package com.tracer.edt.mcp.dto;

public record DebugSessionInfo(
    String sessionId, String projectName, String state,
    int targetCount, String activeThreadId
) {}
