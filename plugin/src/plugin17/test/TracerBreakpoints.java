package plugin17.test;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.JDIDebugModel;

import java.util.*;

/**
 * Manages breakpoints created from trace data (P10).
 * Can set breakpoints at the start or end of each procedure visited during tracing.
 */
public class TracerBreakpoints {

    private final List<IBreakpoint> managedBreakpoints = new ArrayList<>();

    /**
     * Set breakpoints from trace entries.
     * @param entries the trace entries to analyze
     * @param mode "start" = first line of each procedure, "end" = last line
     * @return number of breakpoints created
     */
    public int setFromTrace(List<StepEntry> entries, String mode) {
        if (entries == null || entries.isEmpty()) return 0;

        // Group by (module, procedure) → find first/last line
        Map<String, int[]> procLines = new LinkedHashMap<>(); // key: "module|procedure" → [minLine, maxLine]

        for (StepEntry entry : entries) {
            String key = entry.module() + "|" + entry.procedure();
            int[] range = procLines.computeIfAbsent(key, k -> new int[]{entry.line(), entry.line()});
            range[0] = Math.min(range[0], entry.line());
            range[1] = Math.max(range[1], entry.line());
        }

        int count = 0;
        for (Map.Entry<String, int[]> e : procLines.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            String modulePath = parts[0];
            String procedure = parts.length > 1 ? parts[1] : "";
            int[] range = e.getValue();
            int line = "end".equals(mode) ? range[1] : range[0];

            try {
                IBreakpoint bp = createLineBreakpoint(modulePath, line, procedure);
                if (bp != null) {
                    managedBreakpoints.add(bp);
                    count++;
                }
            } catch (Exception ex) {
                System.err.println("[tracer] Failed to create breakpoint at "
                    + modulePath + ":" + line + " — " + ex.getMessage());
            }
        }
        System.out.println("[tracer] Created " + count + " breakpoints (mode=" + mode + ")");
        return count;
    }

    /**
     * Clear all managed breakpoints.
     */
    public void clearAll() {
        int count = managedBreakpoints.size();
        for (IBreakpoint bp : managedBreakpoints) {
            try {
                bp.delete();
            } catch (CoreException e) {
                System.err.println("[tracer] Failed to delete breakpoint: " + e.getMessage());
            }
        }
        managedBreakpoints.clear();
        System.out.println("[tracer] Cleared " + count + " breakpoints");
    }

    /**
     * List all managed breakpoints.
     */
    public String listAsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < managedBreakpoints.size(); i++) {
            if (i > 0) sb.append(",");
            IBreakpoint bp = managedBreakpoints.get(i);
            sb.append("{");
            try {
                sb.append("\"id\":\"").append(bp.getMarker().getId()).append("\"");
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

    /**
     * Create a line breakpoint at the given module:line.
     * Module path format: "L/projectName/src/package/Class.java" or "src/package/Class.java"
     */
    private IBreakpoint createLineBreakpoint(String modulePath, int line, String procedure) throws CoreException {
        IResource resource = resolveResource(modulePath);
        if (resource == null) {
            System.err.println("[tracer] Cannot resolve resource: " + modulePath);
            return null;
        }

        // Extract type name from module path for the breakpoint
        String typeName = extractTypeName(modulePath);

        IJavaLineBreakpoint bp = JDIDebugModel.createLineBreakpoint(
            resource,           // IResource
            typeName,           // type name
            line,               // line number
            -1,                 // charStart (-1 = not specified)
            -1,                 // charEnd
            0,                  // hitCount (0 = always)
            true,               // register with breakpoint manager
            null                // attributes (null = defaults)
        );

        // Set a marker attribute for identification
        bp.getMarker().setAttribute("message",
            "[tracer] " + procedure + " @ " + resource.getName() + ":" + line);

        return bp;
    }

    /**
     * Resolve module path to IResource.
     * Handles formats like:
     *   "L/tracer_test_17/src/com/test/TracerTestApp.java"
     *   "/tracer_test_17/src/com/test/TracerTestApp.java"
     *   "src/com/test/TracerTestApp.java"
     */
    private IResource resolveResource(String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) return null;

        String path = modulePath;
        // Strip leading "L/" if present
        if (path.startsWith("L/")) path = path.substring(2);
        // Strip leading "/" if present
        if (path.startsWith("/")) path = path.substring(1);

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Try workspace-relative path first
        IFile file = root.getFile(new org.eclipse.core.runtime.Path(path));
        if (file != null && file.exists()) return file;

        // Try to extract project name and find file
        int slash = path.indexOf('/');
        if (slash > 0) {
            String projectName = path.substring(0, slash);
            String filePath = path.substring(slash + 1);
            IProject project = root.getProject(projectName);
            if (project != null && project.exists()) {
                IFile projectFile = project.getFile(filePath);
                if (projectFile != null && projectFile.exists()) return projectFile;
            }
        }

        // Search all projects
        try {
            for (IProject project : root.getProjects()) {
                if (!project.isOpen()) continue;
                IFile found = findFileInProject(project, path);
                if (found != null) return found;
            }
        } catch (Exception e) { /* skip */ }

        return null;
    }

    private IFile findFileInProject(IProject project, String fileName) {
        // Simple: try to find file by name in common source directories
        String[] srcDirs = {"src", "source", "java"};
        for (String dir : srcDirs) {
            IFile file = project.getFile(dir + "/" + fileName);
            if (file != null && file.exists()) return file;
        }
        // Try extracting just the filename
        int lastSlash = fileName.lastIndexOf('/');
        if (lastSlash >= 0) {
            String simpleName = fileName.substring(lastSlash + 1);
            return findFileRecursive(project, simpleName);
        }
        return null;
    }

    private IFile findFileRecursive(IResource container, String fileName) {
        try {
            if (container.getType() == IResource.FILE) {
                if (container.getName().equals(fileName)) return (IFile) container;
                return null;
            }
            if (container instanceof org.eclipse.core.resources.IContainer) {
                for (IResource member : ((org.eclipse.core.resources.IContainer) container).members()) {
                    IFile found = findFileRecursive(member, fileName);
                    if (found != null) return found;
                }
            }
        } catch (CoreException e) { /* skip */ }
        return null;
    }

    /**
     * Extract fully qualified type name from module path.
     * "L/project/src/com/test/MyClass.java" → "com.test.MyClass"
     */
    private String extractTypeName(String modulePath) {
        String path = modulePath;
        if (path.startsWith("L/")) path = path.substring(2);
        if (path.startsWith("/")) path = path.substring(1);

        // Find src/ boundary
        int srcIdx = path.indexOf("/src/");
        String relative;
        if (srcIdx >= 0) {
            relative = path.substring(srcIdx + 5); // after "/src/"
        } else {
            // Try to find the .java file directly
            relative = path;
            int slash = relative.indexOf('/');
            if (slash >= 0) relative = relative.substring(slash + 1);
        }

        // Convert path to type name: "com/test/MyClass.java" → "com.test.MyClass"
        if (relative.endsWith(".java")) relative = relative.substring(0, relative.length() - 5);
        return relative.replace('/', '.').replace('\\', '.');
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
