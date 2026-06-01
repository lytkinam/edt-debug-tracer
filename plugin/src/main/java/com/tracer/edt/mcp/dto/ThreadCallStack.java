package com.tracer.edt.mcp.dto;

import java.util.List;

public record ThreadCallStack(
    String threadId, String threadName, String threadState,
    List<StackFrameInfo> frames
) {}
