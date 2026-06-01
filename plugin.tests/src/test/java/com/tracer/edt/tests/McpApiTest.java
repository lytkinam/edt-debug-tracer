package com.tracer.edt.tests;

import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/**
 * Smoke tests for MCP HTTP API.
 * Tests are skipped if server is not running (used in integration CI stage).
 */
public class McpApiTest {

    private static final String BASE = "http://localhost:18080/mcp";
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
            .header("Content-Type", "application/json")
            .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void testHealth() throws Exception {
        HttpResponse<String> r;
        try { r = get("/health"); } catch (Exception e) { Assume.assumeNoException("MCP server not running", e); return; }
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"ok\""));
    }

    @Test
    public void testStartStopCycle() throws Exception {
        HttpResponse<String> r;
        try { r = post("/start", "{\"session_id\":\"java-test-1\"}"); }
        catch (Exception e) { Assume.assumeNoException("MCP server not running", e); return; }
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("started"));

        r = post("/stop", null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("stopped"));
    }

    @Test
    public void testDoubleStartReturns409() throws Exception {
        HttpResponse<String> r;
        try {
            post("/start", "{\"session_id\":\"java-test-2\"}");
            r = post("/start", "{\"session_id\":\"java-test-2b\"}");
        } catch (Exception e) { Assume.assumeNoException("MCP server not running", e); return; }
        assertEquals(409, r.statusCode());
        post("/stop", null);
    }
}
