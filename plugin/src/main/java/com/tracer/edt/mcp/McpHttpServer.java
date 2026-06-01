package com.tracer.edt.mcp;

import com.tracer.edt.core.*;
import com.tracer.edt.db.TraceRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Minimal HTTP server exposing the MCP API on localhost:18080.
 * Uses built-in com.sun.net.httpserver (no extra dependencies).
 */
public class McpHttpServer {

    private static final Logger LOG = Logger.getLogger(McpHttpServer.class.getName());
    private static final String VERSION = "1.0.0";

    private final int port;
    private final TraceSessionManager sessionManager;
    private final AsyncTraceWriter writer;
    private final TraceRepository repo;
    private HttpServer server;

    public McpHttpServer(int port, TraceSessionManager sm, AsyncTraceWriter writer, TraceRepository repo) {
        this.port = port;
        this.sessionManager = sm;
        this.writer = writer;
        this.repo = repo;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/mcp/health",      this::handleHealth);
        server.createContext("/mcp/status",      this::handleStatus);
        server.createContext("/mcp/start",       this::handleStart);
        server.createContext("/mcp/stop",        this::handleStop);
        server.createContext("/mcp/postprocess", this::handlePostprocess);
        server.createContext("/mcp/trace",       this::handleTrace);
        server.setExecutor(null);
        server.start();
        LOG.info("MCP HTTP server started on port " + port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // --- handlers ---

    private void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, "{\"ok\":true,\"version\":\"" + VERSION + "\"}");
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        if (sessionManager.isActive()) {
            respond(ex, 200, "{\"active\":true,\"session_id\":\"" + sessionManager.getActiveSessionId()
                + "\",\"steps\":" + sessionManager.getStepCount() + "}");
        } else {
            respond(ex, 200, "{\"active\":false}");
        }
    }

    private void handleStart(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String sessionId = extractJsonString(body, "session_id");
        if (sessionId == null || sessionId.isBlank()) {
            respond(ex, 400, "{\"error\":\"session_id required\"}");
            return;
        }
        if (!sessionManager.start(sessionId)) {
            respond(ex, 409, "{\"error\":\"session already active\",\"session_id\":\""
                + sessionManager.getActiveSessionId() + "\"}");
            return;
        }
        try {
            repo.startSession(sessionId);
        } catch (SQLException e) {
            LOG.warning("DB error on start: " + e.getMessage());
        }
        respond(ex, 200, "{\"started\":true,\"session_id\":\"" + sessionId + "\"}");
    }

    private void handleStop(HttpExchange ex) throws IOException {
        String sessionId = sessionManager.getActiveSessionId();
        long steps = sessionManager.getStepCount();
        sessionManager.stop();
        if (sessionId != null) {
            try { repo.stopSession(sessionId); } catch (SQLException e) { LOG.warning(e.getMessage()); }
        }
        respond(ex, 200, "{\"stopped\":true,\"session_id\":\""
            + (sessionId != null ? sessionId : "") + "\",\"steps\":" + steps + "}");
    }

    private void handlePostprocess(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String sessionId = extractJsonString(body, "session_id");
        if (sessionId == null || sessionId.isBlank()) {
            respond(ex, 400, "{\"error\":\"session_id required\"}");
            return;
        }
        try {
            List<TraceEntry> raw = repo.loadRaw(sessionId);
            List<CollapsedTraceEntry> clean = new LoopCollapser().collapse(raw);
            repo.replaceClean(sessionId, clean);
            respond(ex, 200, "{\"ok\":true,\"raw\":" + raw.size() + ",\"clean\":" + clean.size() + "}");
        } catch (SQLException e) {
            respond(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleTrace(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String sessionId = getQueryParam(query, "session");
        String type = getQueryParam(query, "type");
        if (type == null) type = "clean";
        if (sessionId == null || sessionId.isBlank()) {
            respond(ex, 400, "{\"error\":\"session param required\"}");
            return;
        }
        try {
            String json = repo.traceAsJson(sessionId, type);
            respond(ex, 200, json);
        } catch (SQLException e) {
            respond(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // --- helpers ---

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) { return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static String extractJsonString(String json, String key) {
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

    private static String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
