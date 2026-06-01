package com.tracer.edt.mcp.dto;

public record StackFrameInfo(
    String frameId, String frameName, int lineNumber,
    int charStart, int charEnd, int variableCount
) {}
