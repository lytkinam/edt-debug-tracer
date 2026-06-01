package com.tracer.edt.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tracer.edt.core.StepLogBuffer;
import com.tracer.edt.core.TraceEntry;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal HTTP server exposing the MCP API on localhost.
 *
 * Endpoints:
 *   GET  /mcp/health   — liveness check
 *   GET  /mcp/status   — tracing state
 *   POST /mcp/start    — begin trace session
 *   POST /mcp/stop     — end session, return JSON trace log
 */
public class McpHttpServer {

    private final int           port;
    private final StepLogBuffer buffer;
    private       HttpServer    server;

    public McpHttpServer(int port, StepLogBuffer buffer) {
        this.port   = port;
        this.buffer = buffer;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/mcp/health", this::handleHealth);
        server.createContext("/mcp/status", this::handleStatus);
        server.createContext("/mcp/start",  this::handleStart);
        server.createContext("/mcp/stop",   this::handleStop);
        server.setExecutor(null); // default executor
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, "{\"status\":\"ok\",\"version\":\"1.0.0\"}");
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        String sid = buffer.getSessionId();
        String json = String.format(
            "{\"tracing\":%b,\"entries_count\":%d,\"session_id\":%s}",
            buffer.isRecording(),
            buffer.size(),
            sid == null ? "null" : "\"" + sid + "\""
        );
        respond(ex, 200, json);
    }

    private void handleStart(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respond(ex, 405, "{\"error\":\"method_not_allowed\"}"); return; }
        // Читаем опциональный session_id из тела
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String sid  = parseSessionId(body);
        if (buffer.startSession(sid)) {
            respond(ex, 200, String.format("{\"started\":true,\"session_id\":%s}",
                sid == null ? "null" : "\"" + sid + "\""));
        } else {
            respond(ex, 409, "{\"error\":\"already_running\",\"message\":\"Tracing session is already active\"}");
        }
    }

    private void handleStop(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respond(ex, 405, "{\"error\":\"method_not_allowed\"}"); return; }
        if (!buffer.isRecording()) {
            respond(ex, 409, "{\"error\":\"not_running\",\"message\":\"No active tracing session\"}");
            return;
        }
        String sid     = buffer.getSessionId();
        List<TraceEntry> entries = buffer.stopSession();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"stopped\":true,\"session_id\":");
        sb.append(sid == null ? "null" : "\"" + sid + "\"");
        sb.append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(entries.get(i).toJson());
        }
        sb.append("]}");
        respond(ex, 200, sb.toString());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void respond(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    /** Very simple JSON field extractor — avoids external lib dependency. */
    private static String parseSessionId(String json) {
        if (json == null || !json.contains("session_id")) return null;
        int i = json.indexOf("session_id");
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int start = json.indexOf('"', colon);
        int end   = json.indexOf('"', start + 1);
        if (start < 0 || end < 0) return null;
        return json.substring(start + 1, end);
    }
}
