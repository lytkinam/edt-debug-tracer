package com.tracer.edt.mcp.dto;

/** Request for step operation. */
public record StepRequest(String projectName, String threadId, String kind) {}
