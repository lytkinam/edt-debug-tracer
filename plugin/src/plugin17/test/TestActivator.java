package plugin17.test;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

public class TestActivator implements BundleActivator {
    private HttpServer server;
    private TracerListener tracer;
    private int port = 18080;
    private String outputPath;

    @Override
    public void start(BundleContext ctx) throws Exception {
        // Read config from workspace, not from user.home
        String workspacePath = Platform.getInstanceLocation().getURL().getPath();
        File cfgDir = new File(workspacePath, ".edt-debug-tracer");
        File cfgFile = new File(cfgDir, "tracer.properties");

        Properties props = new Properties();
        if (cfgFile.exists()) {
            try (FileInputStream fis = new FileInputStream(cfgFile)) { props.load(fis); }
        }

        // Port: system property > config file > default
        String portStr = System.getProperty("tracer.port");
        if (portStr == null) portStr = props.getProperty("port");
        if (portStr != null) port = Integer.parseInt(portStr.trim());

        // Output path: config file > default
        outputPath = props.getProperty("output");
        if (outputPath == null) outputPath = new File(cfgDir, "trace.json").getAbsolutePath();
        outputPath = outputPath.replace("~", System.getProperty("user.home"));

        tracer = new TracerListener();
        tracer.setOutputPath(outputPath);
        tracer.setConfig(props);
        DebugPlugin.getDefault().addDebugEventListener(tracer);

        server = HttpServer.create(new java.net.InetSocketAddress("localhost", port), 0);
        server.createContext("/mcp/health", ex ->
            respond(ex, 200, "{\"ok\":true,\"recording\":" + tracer.isRecording()
                + ",\"autoStepping\":" + tracer.isAutoStepping()
                + ",\"entries\":" + tracer.size() + ",\"port\":" + port + "}"));

        server.createContext("/mcp/start", ex -> {
            tracer.startRecording();
            respond(ex, 200, "{\"started\":true}");
        });

        server.createContext("/mcp/run", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int steps = 1000;
            int idx = body.indexOf("\"steps\"");
            if (idx >= 0) {
                int colon = body.indexOf(':', idx);
                int start = colon + 1;
                while (start < body.length() && !Character.isDigit(body.charAt(start))) start++;
                int end = start;
                while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
                if (end > start) steps = Integer.parseInt(body.substring(start, end));
            }
            tracer.startAutoStep(steps);
            respond(ex, 200, "{\"autoStep\":true,\"maxSteps\":" + steps + "}");
        });

        server.createContext("/mcp/stop", ex -> {
            List<StepEntry> entries = tracer.stopRecording();
            respond(ex, 200, "{\"stopped\":true,\"count\":" + entries.size()
                + ",\"totalSteps\":" + tracer.getTotalSteps()
                + ",\"file\":\"" + esc(outputPath) + "\"}");
        });

        server.setExecutor(null);
        server.start();
        System.out.println("[tracer] Active on :" + port + ", workspace: " + workspacePath);
    }

    @Override
    public void stop(BundleContext ctx) throws Exception {
        if (server != null) server.stop(0);
        if (tracer != null) DebugPlugin.getDefault().removeDebugEventListener(tracer);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
