package com.tracer.edt.mcp.dto;

import java.util.List;

public record GetCallStackResult(
    String projectName, String activeThreadId,
    List<ThreadCallStack> threads
) {}
