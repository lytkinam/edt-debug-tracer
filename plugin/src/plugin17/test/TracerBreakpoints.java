package plugin17.test;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.model.IBreakpoint;
import org.osgi.framework.Bundle;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Manages breakpoints created from trace data.
 * Uses reflective class loading for JDIDebugModel to avoid OSGi NoClassDefFoundError.
 */
public class TracerBreakpoints {

    private final List<IBreakpoint> managedBreakpoints = new ArrayList<>();
    private Method createLineBreakpointMethod;
    private boolean jdtDebugAvailable = false;

    public TracerBreakpoints() {
        initJdtDebug();
    }

    /**
     * Initialize JDT Debug API via bundle classloader.
     */
    private void initJdtDebug() {
        try {
            Bundle bundle = Platform.getBundle("org.eclipse.jdt.debug");
            if (bundle == null) {
                System.err.println("[tracer] org.eclipse.jdt.debug bundle not found");
                return;
            }
            int state = bundle.getState();
            System.out.println("[tracer] org.eclipse.jdt.debug state: " + state
                + " (INSTALLED=2, RESOLVED=4, STARTING=8, STOPPING=16, ACTIVE=32)");

            // Start bundle if not active
            if (state != Bundle.ACTIVE) {
                System.out.println("[tracer] Starting org.eclipse.jdt.debug bundle...");
                bundle.start(Bundle.START_ACTIVATION_POLICY);
                System.out.println("[tracer] Bundle state after start: " + bundle.getState());
            }

            // Load class via bundle classloader
            Class<?> modelClass = bundle.loadClass("org.eclipse.jdt.debug.core.JDIDebugModel");
            createLineBreakpointMethod = modelClass.getMethod("createLineBreakpoint",
                IResource.class, String.class, int.class, int.class, int.class, int.class, boolean.class, Map.class);
            jdtDebugAvailable = true;
            System.out.println("[tracer] JDIDebugModel loaded successfully via bundle classloader");
        } catch (Exception e) {
            System.err.println("[tracer] JDT Debug init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            jdtDebugAvailable = false;
        }
    }

    /**
     * Set breakpoints from trace entries.
     */
    public int setFromTrace(List<StepEntry> entries, String mode) {
        if (entries == null || entries.isEmpty()) return 0;
        if (!jdtDebugAvailable) {
            System.err.println("[tracer] JDT Debug not available, cannot set breakpoints");
            return 0;
        }

        // Group by (module, procedure) -> find first/last line
        Map<String, int[]> procLines = new LinkedHashMap<>();
        for (StepEntry entry : entries) {
            String key = entry.module() + "|" + entry.procedure();
            int[] range = procLines.computeIfAbsent(key, k -> new int[]{entry.line(), entry.line()});
            range[0] = Math.min(range[0], entry.line());
            range[1] = Math.max(range[1], entry.line());
        }

        final int[] count = {0};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        org.eclipse.core.runtime.jobs.Job.create("Tracer: Set Breakpoints", monitor -> {
            for (Map.Entry<String, int[]> e : procLines.entrySet()) {
                String[] parts = e.getKey().split("\\|", 2);
                String modulePath = parts[0];
                String procedure = parts.length > 1 ? parts[1] : "";
                int line = "end".equals(mode) ? e.getValue()[1] : e.getValue()[0];

                try {
                    IResource resource = resolveResource(modulePath);
                    if (resource == null) {
                        System.err.println("[tracer] Cannot resolve: " + modulePath);
                        continue;
                    }
                    String typeName = extractTypeName(modulePath);

                    // Create breakpoint via reflection
                    IBreakpoint bp = (IBreakpoint) createLineBreakpointMethod.invoke(
                        null, resource, typeName, line, -1, -1, 0, true, null);

                    if (bp != null) {
                        bp.getMarker().setAttribute("message",
                            "[tracer] " + procedure + " @ " + resource.getName() + ":" + line);
                        managedBreakpoints.add(bp);
                        count[0]++;
                    }
                } catch (Exception ex) {
                    System.err.println("[tracer] BP error " + modulePath + ":" + line
                        + " — " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
            System.out.println("[tracer] Created " + count[0] + " breakpoints (mode=" + mode + ")");
            latch.countDown();
            return org.eclipse.core.runtime.Status.OK_STATUS;
        }).schedule();

        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return count[0];
    }

    /**
     * Clear all managed breakpoints.
     */
    public void clearAll() {
        int count = managedBreakpoints.size();
        for (IBreakpoint bp : managedBreakpoints) {
            try { bp.delete(); } catch (CoreException e) { /* skip */ }
        }
        managedBreakpoints.clear();
        System.out.println("[tracer] Cleared " + count + " breakpoints");
    }

    /**
     * List managed breakpoints as JSON.
     */
    public String listAsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < managedBreakpoints.size(); i++) {
            if (i > 0) sb.append(",");
            IBreakpoint bp = managedBreakpoints.get(i);
            sb.append("{");
            try {
                sb.append("\"id\":").append(bp.getMarker().getId());
                sb.append(",\"resource\":\"").append(esc(bp.getMarker().getResource().getName())).append("\"");
                sb.append(",\"line\":").append(bp.getMarker().getAttribute("line", -1));
                sb.append(",\"enabled\":").append(bp.isEnabled());
                String msg = bp.getMarker().getAttribute("message", "");
                sb.append(",\"message\":\"").append(esc(msg)).append("\"");
            } catch (CoreException e) {
                sb.append("\"error\":\"").append(esc(e.getMessage())).append("\"");
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    public int size() { return managedBreakpoints.size(); }
    public boolean isAvailable() { return jdtDebugAvailable; }

    // --- Resource resolution ---

    private IResource resolveResource(String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) return null;
        String path = modulePath;
        if (path.startsWith("L/")) path = path.substring(2);
        if (path.startsWith("/")) path = path.substring(1);

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Workspace-relative
        IFile file = root.getFile(new org.eclipse.core.runtime.Path(path));
        if (file != null && file.exists()) return file;

        // Extract project name
        int slash = path.indexOf('/');
        if (slash > 0) {
            String projectName = path.substring(0, slash);
            String filePath = path.substring(slash + 1);
            IProject project = root.getProject(projectName);
            if (project != null && project.exists()) {
                IFile pf = project.getFile(filePath);
                if (pf != null && pf.exists()) return pf;
                // Try src/ prefix
                pf = project.getFile("src/" + filePath);
                if (pf != null && pf.exists()) return pf;
            }
        }
        return null;
    }

    private String extractTypeName(String modulePath) {
        String path = modulePath;
        if (path.startsWith("L/")) path = path.substring(2);
        if (path.startsWith("/")) path = path.substring(1);
        int srcIdx = path.indexOf("/src/");
        String relative;
        if (srcIdx >= 0) {
            relative = path.substring(srcIdx + 5);
        } else {
            relative = path;
            int slash = relative.indexOf('/');
            if (slash >= 0) relative = relative.substring(slash + 1);
        }
        if (relative.endsWith(".java")) relative = relative.substring(0, relative.length() - 5);
        return relative.replace('/', '.').replace('\\', '.');
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
