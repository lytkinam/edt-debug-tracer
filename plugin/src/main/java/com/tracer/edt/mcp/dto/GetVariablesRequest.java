package com.tracer.edt.mcp.dto;

public record GetVariablesRequest(String projectName, String threadId, String frameId) {}
