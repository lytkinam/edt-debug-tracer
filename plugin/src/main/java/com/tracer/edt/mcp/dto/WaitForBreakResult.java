package com.tracer.edt.mcp.dto;

public record WaitForBreakResult(
    String projectName, boolean suspended, String threadId,
    String reason, long elapsedMs, boolean timeout
) {}
