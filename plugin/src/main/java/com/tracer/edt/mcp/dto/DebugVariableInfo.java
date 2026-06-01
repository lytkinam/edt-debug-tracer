package com.tracer.edt.mcp.dto;

public record DebugVariableInfo(String name, String type, String value, boolean hasChildren) {}
