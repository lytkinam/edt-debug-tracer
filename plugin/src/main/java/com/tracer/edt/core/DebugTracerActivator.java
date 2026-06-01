package com.tracer.edt.core;

import com.tracer.edt.mcp.McpHttpServer;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

/**
 * OSGi bundle activator.
 * Registers the debug event listener and starts the MCP HTTP server on bundle start.
 */
public class DebugTracerActivator extends Plugin {

    public static final String PLUGIN_ID = "com.tracer.edt.debugtracer";
    private static DebugTracerActivator instance;

    private DebugTracerListener listener;
    private McpHttpServer httpServer;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;

        // 1. Создаём буфер трейса
        StepLogBuffer buffer = new StepLogBuffer();

        // 2. Регистрируем слушатель событий отладчика
        listener = new DebugTracerListener(buffer);
        DebugPlugin.getDefault().addDebugEventListener(listener);

        // 3. Запускаем MCP HTTP сервер
        int port = Integer.getInteger("edt.tracer.port", 18080);
        httpServer = new McpHttpServer(port, buffer);
        httpServer.start();

        getLog().info(PLUGIN_ID + " started. MCP HTTP server on port " + port);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        // Останавливаем в обратном порядке
        if (httpServer != null) {
            httpServer.stop();
        }
        if (listener != null) {
            DebugPlugin.getDefault().removeDebugEventListener(listener);
        }
        instance = null;
        super.stop(context);
    }

    public static DebugTracerActivator getInstance() {
        return instance;
    }
}
