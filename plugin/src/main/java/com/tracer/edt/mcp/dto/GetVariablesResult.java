package com.tracer.edt.mcp.dto;

import java.util.List;

public record GetVariablesResult(
    String projectName, String threadId, String frameId,
    int lineNumber, String frameName,
    String sourcePath, int charStart, int charEnd,
    List<DebugVariableInfo> variables
) {}
