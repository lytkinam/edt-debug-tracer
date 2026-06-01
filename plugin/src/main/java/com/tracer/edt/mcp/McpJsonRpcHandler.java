package com.tracer.edt.mcp;

import com.tracer.edt.debug.DebugService;
import com.tracer.edt.mcp.dto.*;

import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON-RPC 2.0 handler for MCP protocol.
 * Supports tools/list and tools/call with the same tool names as codepilot1c-edt.
 */
public class McpJsonRpcHandler {

    private static final Logger LOG = Logger.getLogger(McpJsonRpcHandler.class.getName());
    private final DebugService debugService;

    public McpJsonRpcHandler(DebugService debugService) {
        this.debugService = debugService;
    }

    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            String method = extractString(body, "method");
            String id = extractRaw(body, "id");
            if (id == null) id = "1";

            if ("tools/list".equals(method)) {
                respond(ex, 200, wrapResult(id, toolsListJson()));
            } else if ("tools/call".equals(method)) {
                String paramsJson = extractObject(body, "params");
                String toolName = extractString(paramsJson, "name");
                String argsJson = extractObject(paramsJson, "arguments");
                String result = callTool(toolName, argsJson);
                respond(ex, 200, wrapResult(id, result));
            } else {
                respond(ex, 200, wrapError(id, -32601, "Method not found: " + method));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "JSON-RPC error", e);
            respond(ex, 200, wrapError("1", -32603, e.getMessage()));
        }
    }

    // ── Tool Dispatch ──────────────────────────────────────────────────────

    private String callTool(String name, String args) {
        if (name == null) return errorResult("Tool name required");
        return switch (name) {
            case "debug_status" -> {
                var req = new DebugStatusRequest(str(args, "projectName"));
                yield toJson(debugService.debugStatus(req));
            }
            case "step" -> {
                var req = new StepRequest(str(args, "projectName"), str(args, "threadId"), str(args, "kind"));
                yield toJson(debugService.step(req));
            }
            case "resume" -> {
                var req = new ResumeRequest(str(args, "projectName"), str(args, "threadId"));
                yield toJson(debugService.resume(req));
            }
            case "suspend" -> {
                var req = new SuspendRequest(str(args, "projectName"), str(args, "threadId"));
                yield toJson(debugService.suspend(req));
            }
            case "wait_for_break" -> {
                Long timeout = lng(args, "timeoutMs");
                var req = new WaitForBreakRequest(str(args, "projectName"), timeout);
                yield toJson(debugService.waitForBreak(req));
            }
            case "get_variables" -> {
                var req = new GetVariablesRequest(str(args, "projectName"), str(args, "threadId"), str(args, "frameId"));
                yield toJson(debugService.getVariables(req));
            }
            case "get_call_stack" -> {
                var req = new GetCallStackRequest(str(args, "projectName"), str(args, "threadId"));
                yield toJson(debugService.getCallStack(req));
            }
            case "list_sessions" -> {
                var req = new ListSessionsRequest(str(args, "projectName"));
                yield toJson(debugService.listSessions(req));
            }
            default -> errorResult("Unknown tool: " + name);
        };
    }

    // ── Tool Definitions ───────────────────────────────────────────────────

    private String toolsListJson() {
        List<String> tools = new ArrayList<>();
        tools.add(toolDef("debug_status", "Current debug state overview",
            "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[]}"));
        tools.add(toolDef("step", "Step into/over/out in debug",
            "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"threadId\":{\"type\":\"string\"},\"kind\":{\"type\":\"string\",\"enum\":[\"into\",\"over\",\"out\"]}},\"required\":[\"projectName\"]}"));
        tools.add(toolDef("resume", "Resume suspended debug thread",
            "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"threadId\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"));
        tools.add(toolDef("suspend", "Suspend running debug thread",
            "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"threadId\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"));
        tools.add(toolDef("wait_for_break", "Wait for debug suspend event",
            "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"timeoutMs\":{\"type\":\"integer\"}},\"required\":[\"projectName\"]}"));
        tools.add(toolDef("get_variables", "Get stack frame variables and position",
            "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"threadId\":{\"type\":\"string\"},\"frameId\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"));
        tools.add(toolDef("get_call_stack", "Get full call stack for all threads",
            "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"threadId\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"));
        tools.add(toolDef("list_sessions", "List active debug sessions",
            "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[]}"));
        return "{\"tools\":[" + String.join(",", tools) + "]}";
    }

    private static String toolDef(String name, String description, String inputSchema) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description + "\",\"inputSchema\":" + inputSchema + "}";
    }

    // ── JSON Serialization ─────────────────────────────────────────────────

    private String toJson(DebugStatusResult r) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + q(
            "{\"projectName\":" + qn(r.projectName()) + ",\"state\":" + q(r.state())
            + ",\"suspended\":" + r.suspended() + ",\"launchCount\":" + r.launchCount()
            + ",\"targetCount\":" + r.targetCount() + ",\"breakpointCount\":" + r.breakpointCount()
            + ",\"activeThreadId\":" + qn(r.activeThreadId()) + ",\"message\":" + q(r.message()) + "}")
            + "}]}";
    }

    private String toJson(StepResult r) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + q(
            "{\"projectName\":" + qn(r.projectName()) + ",\"threadId\":" + qn(r.threadId())
            + ",\"kind\":" + q(r.kind()) + ",\"status\":" + q(r.status()) + ",\"message\":" + q(r.message()) + "}")
            + "}]}";
    }

    private String toJson(ResumeResult r) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + q(
            "{\"projectName\":" + qn(r.projectName()) + ",\"threadId\":" + qn(r.threadId())
            + ",\"status\":" + q(r.status()) + ",\"message\":" + q(r.message()) + "}")
            + "}]}";
    }

    private String toJson(SuspendResult r) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + q(
            "{\"projectName\":" + qn(r.projectName()) + ",\"threadId\":" + qn(r.threadId())
            + ",\"status\":" + q(r.status()) + ",\"message\":" + q(r.message()) + "}")
            + "}]}";
    }

    private String toJson(WaitForBreakResult r) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + q(
            "{\"projectName\":" + qn(r.projectName()) + ",\"suspended\":" + r.suspended()
            + ",\"threadId\":" + qn(r.threadId()) + ",\"reason\":" + q(r.reason())
            + ",\"elapsedMs\":" + r.elapsedMs() + ",\"timeout\":" + r.timeout() + "}")
            + "}]}";
    }

    private String toJson(GetVariablesResult r) {
        StringBuilder vars = new StringBuilder("[");
        boolean first = true;
        for (DebugVariableInfo v : r.variables()) {
            if (!first) vars.append(",");
            first = false;
            vars.append("{\"name\":").append(q(v.name()))
                .append(",\"type\":").append(qn(v.type()))
                .append(",\"value\":").append(qn(v.value()))
                .append(",\"hasChildren\":").append(v.hasChildren()).append("}");
        }
        vars.append("]");
        return "{\"content\":[{\"type\":\"text\",\"text\":" + q(
            "{\"projectName\":" + qn(r.projectName()) + ",\"threadId\":" + qn(r.threadId())
            + ",\"frameId\":" + qn(r.frameId()) + ",\"lineNumber\":" + r.lineNumber()
            + ",\"frameName\":" + qn(r.frameName()) + ",\"sourcePath\":" + qn(r.sourcePath())
            + ",\"charStart\":" + r.charStart() + ",\"charEnd\":" + r.charEnd()
            + ",\"variables\":" + vars + "}")
            + "}]}";
    }

    private String toJson(GetCallStackResult r) {
        StringBuilder threads = new StringBuilder("[");
        boolean first = true;
        for (ThreadCallStack t : r.threads()) {
            if (!first) threads.append(",");
            first = false;
            StringBuilder frames = new StringBuilder("[");
            boolean ff = true;
            for (StackFrameInfo f : t.frames()) {
                if (!ff) frames.append(",");
                ff = false;
                frames.append("{\"frameId\":").append(q(f.frameId()))
                    .append(",\"frameName\":").append(q(f.frameName()))
                    .append(",\"lineNumber\":").append(f.lineNumber())
                    .append(",\"charStart\":").append(f.charStart())
                    .append(",\"charEnd\":").append(f.charEnd())
                    .append(",\"variableCount\":").append(f.variableCount()).append("}");
            }
            frames.append("]");
            threads.append("{\"threadId\":").append(q(t.threadId()))
                .append(",\"threadName\":").append(q(t.threadName()))
                .append(",\"threadState\":").append(q(t.threadState()))
                .append(",\"frames\":").append(frames).append("}");
        }
        threads.append("]");
        return "{\"content\":[{\"type\":\"text\",\"text\":" + q(
            "{\"projectName\":" + qn(r.projectName()) + ",\"activeThreadId\":" + qn(r.activeThreadId())
            + ",\"threads\":" + threads + "}")
            + "}]}";
    }

    private String toJson(ListSessionsResult r) {
        StringBuilder sessions = new StringBuilder("[");
        boolean first = true;
        for (DebugSessionInfo s : r.sessions()) {
            if (!first) sessions.append(",");
            first = false;
            sessions.append("{\"sessionId\":").append(q(s.sessionId()))
                .append(",\"projectName\":").append(q(s.projectName()))
                .append(",\"state\":").append(q(s.state()))
                .append(",\"targetCount\":").append(s.targetCount())
                .append(",\"activeThreadId\":").append(qn(s.activeThreadId())).append("}");
        }
        sessions.append("]");
        return "{\"content\":[{\"type\":\"text\",\"text\":" + q("{\"sessions\":" + sessions + "}") + "}]}";
    }

    private String errorResult(String message) {
        return "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":" + q("{\"error\":\"" + esc(message) + "\"}") + "}]}";
    }

    // ── JSON Helpers ───────────────────────────────────────────────────────

    private static String wrapResult(String id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
    }

    private static String wrapError(String id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code + ",\"message\":" + q(message) + "}}";
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    /** Quote a string value for JSON. Null → "null". */
    private static String q(String s) {
        return s == null ? "null" : "\"" + esc(s) + "\"";
    }

    /** Quote or null. */
    private static String qn(String s) { return q(s); }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ── Minimal JSON Parsing ───────────────────────────────────────────────

    private static String extractString(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static String extractRaw(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        // Could be string, number, or null
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start, end + 1);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    private static String extractObject(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int start = json.indexOf('{', colon);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            else if (json.charAt(i) == '}') { depth--; if (depth == 0) return json.substring(start, i + 1); }
        }
        return null;
    }

    private static String str(String json, String key) { return extractString(json, key); }

    private static Long lng(String json, String key) {
        String raw = extractString(json, key);
        if (raw == null) {
            // Try as number
            String search = "\"" + key + "\"";
            if (json == null || !json.contains(search)) return null;
            int idx = json.indexOf(search);
            int colon = json.indexOf(':', idx + search.length());
            if (colon < 0) return null;
            int start = colon + 1;
            while (start < json.length() && json.charAt(start) == ' ') start++;
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            if (end == start) return null;
            try { return Long.parseLong(json.substring(start, end)); } catch (NumberFormatException e) { return null; }
        }
        try { return Long.parseLong(raw); } catch (NumberFormatException e) { return null; }
    }
}
