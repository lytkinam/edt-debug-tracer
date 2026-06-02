package plugin17.test;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TracerIntegrationTest {

    private HttpClient client = HttpClient.newHttpClient();

    @Before
    public void ensureBundleActive() throws Exception {
        Bundle bundle = Platform.getBundle("plugin17-test");
        assertNotNull("Bundle not found", bundle);
        if (bundle.getState() != Bundle.ACTIVE) {
            bundle.start(Bundle.START_TRANSIENT);
        }
        Thread.sleep(1000);
    }

    @Test
    public void testHealthShowsRecording() throws Exception {
        // Initially not recording
        String health = get("/mcp/health");
        assertTrue("Health: " + health, health.contains("\"recording\":false"));
        System.out.println("Initial health: " + health);
    }

    @Test
    public void testStartStopRecording() throws Exception {
        // Start recording
        String start = post("/mcp/start", "{}");
        assertTrue("Start: " + start, start.contains("\"started\":true"));

        // Health should show recording=true
        String health = get("/mcp/health");
        assertTrue("Health during: " + health, health.contains("\"recording\":true"));

        // Stop recording
        String stop = post("/mcp/stop", "{}");
        assertTrue("Stop: " + stop, stop.contains("\"stopped\":true"));
        assertTrue("Stop has count", stop.contains("\"count\":"));
        System.out.println("Stop result: " + stop);
    }

    private String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:18080" + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:18080" + path))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
}
