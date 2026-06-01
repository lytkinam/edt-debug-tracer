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

public class McpHealthTest {

    @Before
    public void ensureBundleActive() throws Exception {
        Bundle bundle = Platform.getBundle("plugin17-test");
        assertNotNull("Bundle plugin17-test not found", bundle);
        System.out.println("Bundle state: " + bundle.getState() + " (ACTIVE=" + Bundle.ACTIVE + ")");
        if (bundle.getState() != Bundle.ACTIVE) {
            bundle.start(Bundle.START_TRANSIENT);
            System.out.println("Bundle started. New state: " + bundle.getState());
        }
        Thread.sleep(1000);
    }

    @Test
    public void testHealthEndpoint() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:18080/mcp/health"))
            .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue("Body: " + resp.body(), resp.body().contains("\"ok\":true"));
        System.out.println("Health: " + resp.body());
    }
}
