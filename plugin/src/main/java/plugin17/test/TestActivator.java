package plugin17.test;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.eclipse.debug.core.DebugPlugin;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TestActivator implements BundleActivator {
    private HttpServer server;
    private TracerListener tracer;

    @Override
    public void start(BundleContext ctx) throws Exception {
        tracer = new TracerListener();
        DebugPlugin.getDefault().addDebugEventListener(tracer);

        server = HttpServer.create(new InetSocketAddress("localhost", 18080), 0);
        server.createContext("/mcp/health", ex -> {
            respond(ex, 200, "{\"ok\":true,\"recording\":" + tracer.isRecording()
                + ",\"entries\":" + tracer.size() + "}");
        });
        server.createContext("/mcp/start", ex -> {
            tracer.startRecording();
            respond(ex, 200, "{\"started\":true}");
        });
        server.createContext("/mcp/stop", ex -> {
            List<StepEntry> entries = tracer.stopRecording();
            StringBuilder sb = new StringBuilder("{\"stopped\":true,\"count\":");
            sb.append(entries.size()).append(",\"entries\":[");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(entries.get(i).toJson());
            }
            sb.append("]}");
            respond(ex, 200, sb.toString());
        });
        server.setExecutor(null);
        server.start();
        System.out.println("[plugin17-test] Tracer active. MCP on :18080");
    }

    @Override
    public void stop(BundleContext ctx) throws Exception {
        if (server != null) server.stop(0);
        if (tracer != null)
            DebugPlugin.getDefault().removeDebugEventListener(tracer);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }
}
