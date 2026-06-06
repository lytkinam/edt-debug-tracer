package plugin17.test;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import java.util.*;

/**
 * Manages breakpoints within the Eclipse/EDT workspace.
 *
 * Added in java_debug_tracer branch:
 *   setLineBreakpoint(typeName, line) — used by TraceSink to place a
 *   breakpoint programmatically from a "ClassName:line" spec.
 */
public class TracerBreakpoints {

    /** Breakpoints created by this manager (so we can selectively clear them). */
    private final List<IBreakpoint> managed = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Public API used by TraceSink
    // -------------------------------------------------------------------------

    /**
     * Sets a Java line breakpoint on {@code typeName} at {@code line}.
     * Uses JDIDebugModel via reflection (same pattern as setFromTrace).
     *
     * @param typeName fully-qualified class name, e.g. "com.example.Foo"
     * @param line     1-based line number
     * @return true if the breakpoint was successfully created
     */
    public boolean setLineBreakpoint(String typeName, int line) {
        try {
            // Try to get a Java resource for the type — fallback to workspace root
            org.eclipse.core.resources.IResource resource = findResource(typeName);

            Class<?> jdiModel = Class.forName("org.eclipse.jdt.debug.core.JDIDebugModel",
                true, getClass().getClassLoader());

            java.lang.reflect.Method m = jdiModel.getMethod(
                "createLineBreakpoint",
                org.eclipse.core.resources.IResource.class,
                String.class, int.class, int.class, int.class, boolean.class,
                java.util.Map.class);

            IBreakpoint bp = (IBreakpoint) m.invoke(null,
                resource, typeName, line,
                -1, -1,          // char start/end — -1 = unknown
                true,            // register with BreakpointManager
                null);           // no attributes map

            if (bp != null) {
                bp.setEnabled(true);
                managed.add(bp);
                System.out.println("[TracerBreakpoints] set bp: " + typeName + ":" + line);
                return true;
            }
        } catch (Exception e) {
            System.err.println("[TracerBreakpoints] setLineBreakpoint error: " + e.getMessage());
        }
        return false;
    }

    /**
     * Sets breakpoints from a trace session (existing behaviour).
     * mode = "start" → first occurrence of each procedure
     * mode = "end"   → last  occurrence of each procedure
     */
    public int setFromTrace(List<StepEntry> entries, String mode) {
        if (entries == null || entries.isEmpty()) return 0;
        Map<String, StepEntry> seen = new LinkedHashMap<>();
        if ("end".equals(mode)) {
            for (StepEntry e : entries) {
                String key = e.module + ":" + e.procedure;
                seen.put(key, e);
            }
        } else {
            for (StepEntry e : entries) {
                String key = e.module + ":" + e.procedure;
                seen.putIfAbsent(key, e);
            }
        }
        int count = 0;
        for (StepEntry e : seen.values()) {
            if (setBreakpointFromEntry(e)) count++;
        }
        return count;
    }

    public void clearAll() {
        for (IBreakpoint bp : managed) {
            try {
                DebugPlugin.getDefault().getBreakpointManager().removeBreakpoint(bp, true);
            } catch (Exception ignore) {}
        }
        managed.clear();
    }

    public String listAsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < managed.size(); i++) {
            IBreakpoint bp = managed.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            try {
                Object m = bp.getMarker();
                if (m != null) {
                    java.lang.reflect.Method getType = m.getClass().getMethod("getType");
                    sb.append("\"type\":\"").append(getType.invoke(m)).append("\"");
                    java.lang.reflect.Method getAttribute = m.getClass().getMethod(
                        "getAttribute", String.class, int.class);
                    int lineNum = (Integer) getAttribute.invoke(m,
                        "org.eclipse.debug.core.model.ILineBreakpoint.LINE", -1);
                    sb.append(",\"line\":").append(lineNum);
                }
            } catch (Exception ignore) {}
            sb.append(",\"enabled\":");
            try { sb.append(bp.isEnabled()); } catch (Exception ex) { sb.append("false"); }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean setBreakpointFromEntry(StepEntry e) {
        try {
            String module = e.module;
            String typeName = moduleToTypeName(module);
            return setLineBreakpoint(typeName, e.line);
        } catch (Exception ex) {
            System.err.println("[TracerBreakpoints] setFromEntry: " + ex.getMessage());
            return false;
        }
    }

    /** Best-effort: derive a Java type name from Eclipse module path. */
    private String moduleToTypeName(String module) {
        // E.g. "L/myproject/src/com/example/Foo.java" → "com.example.Foo"
        if (module == null) return "";
        int slash = module.indexOf('/');
        if (slash >= 0) module = module.substring(slash + 1);
        // Remove everything up to src/
        int src = module.indexOf("/src/");
        if (src >= 0) module = module.substring(src + 5);
        // Remove .java extension
        if (module.endsWith(".java")) module = module.substring(0, module.length() - 5);
        return module.replace('/', '.');
    }

    /** Find an IResource for the type, falling back to workspace root. */
    private org.eclipse.core.resources.IResource findResource(String typeName) {
        try {
            org.eclipse.core.resources.IWorkspaceRoot root =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
            // Try to find the .java file in any project
            String pathPart = typeName.replace('.', '/') + ".java";
            for (org.eclipse.core.resources.IProject proj : root.getProjects()) {
                org.eclipse.core.resources.IResource res =
                    proj.findMember("src/" + pathPart);
                if (res != null && res.exists()) return res;
            }
            return root;  // fallback
        } catch (Exception e) {
            try {
                return org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
