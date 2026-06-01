package com.tracer.edt.core;

import com.tracer.edt.db.TraceRepository;
import com.tracer.edt.mcp.McpHttpServer;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.debug.core.DebugPlugin;
import org.osgi.framework.BundleContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * OSGi bundle activator. Starts/stops all tracer components.
 */
public class DebugTracerActivator extends Plugin {

    private static final Logger LOG = Logger.getLogger(DebugTracerActivator.class.getName());

    private TraceRepository repo;
    private AsyncTraceWriter writer;
    private TraceSessionManager sessionManager;
    private DebugTracerListener listener;
    private McpHttpServer httpServer;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        LOG.info("EDT Debug Tracer starting...");

        Path dbPath = Paths.get(System.getProperty("user.home"), ".edt-debug-tracer", "trace.db");
        dbPath.getParent().toFile().mkdirs();

        repo = new TraceRepository(dbPath);
        repo.init();

        writer = new AsyncTraceWriter(repo);
        writer.start();

        sessionManager = new TraceSessionManager();

        listener = new DebugTracerListener(writer, sessionManager);
        DebugPlugin.getDefault().addDebugEventListener(listener);

        httpServer = new McpHttpServer(18080, sessionManager, writer, repo);
        httpServer.start();

        LOG.info("EDT Debug Tracer started. MCP: http://localhost:18080/mcp/health");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (httpServer != null) httpServer.stop();
        if (listener != null) DebugPlugin.getDefault().removeDebugEventListener(listener);
        if (writer != null) writer.stop();
        if (repo != null) repo.close();
        super.stop(context);
        LOG.info("EDT Debug Tracer stopped.");
    }
}
