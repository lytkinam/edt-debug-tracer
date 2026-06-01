package com.tracer.edt.mcp.dto;

public record SuspendResult(String projectName, String threadId, String status, String message) {}
