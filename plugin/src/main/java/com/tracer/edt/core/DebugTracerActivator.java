package com.tracer.edt.core;

import com.tracer.edt.db.TraceRepository;
import com.tracer.edt.debug.DebugService;
import com.tracer.edt.mcp.McpHttpServer;
import com.tracer.edt.mcp.McpJsonRpcHandler;
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
    private DebugService debugService;
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

        // Debug control service + event-driven wait_for_break
        debugService = new DebugService();
        DebugPlugin.getDefault().addDebugEventListener(debugService.getBreakListener());

        // MCP JSON-RPC handler for debug tools
        McpJsonRpcHandler rpcHandler = new McpJsonRpcHandler(debugService);

        httpServer = new McpHttpServer(18080, sessionManager, writer, repo, rpcHandler);
        httpServer.start();

        LOG.info("EDT Debug Tracer started. MCP: http://localhost:18080/mcp/health | JSON-RPC: POST /mcp");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (httpServer != null) httpServer.stop();
        if (debugService != null) {
            DebugPlugin.getDefault().removeDebugEventListener(debugService.getBreakListener());
        }
        if (listener != null) DebugPlugin.getDefault().removeDebugEventListener(listener);
        if (writer != null) writer.stop();
        if (repo != null) repo.close();
        super.stop(context);
        LOG.info("EDT Debug Tracer stopped.");
    }
}
