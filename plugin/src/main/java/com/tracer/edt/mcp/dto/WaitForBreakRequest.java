package com.tracer.edt.mcp.dto;

public record WaitForBreakRequest(String projectName, Long timeoutMs) {}
