package com.tracer.edt.debug;

import com.tracer.edt.mcp.dto.*;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ISourceLocator;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core debug control service. Direct Eclipse Debug API access (no reflection).
 * Provides step, resume, suspend, getVariables, getCallStack, listSessions, debugStatus.
 */
public class DebugService {

    private static final Logger LOG = Logger.getLogger(DebugService.class.getName());
    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME";

    private final WaitForBreakListener breakListener = new WaitForBreakListener();

    public WaitForBreakListener getBreakListener() { return breakListener; }

    // ── Debug Control ──────────────────────────────────────────────────────

    public StepResult step(StepRequest request) {
        if (request.projectName() == null) return error("projectName required", request);
        try {
            IThread thread = findThread(request.projectName(), request.threadId(), true);
            if (thread == null)
                return new StepResult(request.projectName(), null, request.kind(), "not_found", "No suspended thread");
            String kind = request.kind() != null ? request.kind() : "into";
            switch (kind) {
                case "into" -> thread.stepInto();
                case "over" -> thread.stepOver();
                case "out"  -> thread.stepReturn();
                default -> { return new StepResult(request.projectName(), tid(thread), kind, "error", "Unknown kind: " + kind); }
            }
            return new StepResult(request.projectName(), tid(thread), kind, "started", "Step command sent");
        } catch (DebugException e) {
            return new StepResult(request.projectName(), null, request.kind(), "error", e.getMessage());
        }
    }

    public ResumeResult resume(ResumeRequest request) {
        if (request.projectName() == null) return new ResumeResult(null, null, "error", "projectName required");
        try {
            IThread thread = findThread(request.projectName(), request.threadId(), false);
            if (thread == null)
                return new ResumeResult(request.projectName(), null, "not_found", "No debug thread");
            Object resumable = thread.canResume() ? thread : thread.getDebugTarget();
            if (!((IDebugTarget) resumable).canResume())
                return new ResumeResult(request.projectName(), tid(thread), "not_available", "Cannot resume");
            ((IDebugTarget) resumable).resume();
            return new ResumeResult(request.projectName(), tid(thread), "resumed", "Resume command sent");
        } catch (DebugException e) {
            return new ResumeResult(request.projectName(), null, "error", e.getMessage());
        }
    }

    public SuspendResult suspend(SuspendRequest request) {
        if (request.projectName() == null) return new SuspendResult(null, null, "error", "projectName required");
        try {
            IThread thread = findThread(request.projectName(), request.threadId(), false);
            if (thread == null)
                return new SuspendResult(request.projectName(), null, "not_found", "No debug thread");
            Object suspendable = thread.canSuspend() ? thread : thread.getDebugTarget();
            if (!((IDebugTarget) suspendable).canSuspend())
                return new SuspendResult(request.projectName(), tid(thread), "not_available", "Cannot suspend");
            ((IDebugTarget) suspendable).suspend();
            return new SuspendResult(request.projectName(), tid(thread), "suspended", "Suspend command sent");
        } catch (DebugException e) {
            return new SuspendResult(request.projectName(), null, "error", e.getMessage());
        }
    }

    // ── Data Retrieval ─────────────────────────────────────────────────────

    public GetVariablesResult getVariables(GetVariablesRequest request) {
        if (request.projectName() == null)
            return new GetVariablesResult(null, null, null, 0, null, null, -1, -1, List.of());
        try {
            IThread thread = findThread(request.projectName(), request.threadId(), true);
            if (thread == null)
                return new GetVariablesResult(request.projectName(), null, null, 0, null, null, -1, -1, List.of());

            IStackFrame frame = findFrameInThread(thread, request.frameId());
            if (frame == null)
                return new GetVariablesResult(request.projectName(), tid(thread), null, 0, null, null, -1, -1, List.of());

            int lineNumber = frame.getLineNumber();
            String frameName = frame.getName();
            int charStart = frame.getCharStart();
            int charEnd = frame.getCharEnd();

            // sourcePath via ISourceLocator
            // sourcePath — not available via standard ISourceLocator, reserved for future
            String sourcePath = null;

            // Variables
            List<DebugVariableInfo> variables = new ArrayList<>();
            for (IVariable var : frame.getVariables()) {
                String name = var.getName();
                String type = var.getReferenceTypeName();
                IValue value = var.getValue();
                String valueStr = value != null ? value.getValueString() : null;
                if (type == null && value != null) type = value.getReferenceTypeName();
                boolean hasChildren = value != null && value.hasVariables();
                variables.add(new DebugVariableInfo(name, type, valueStr, hasChildren));
            }

            return new GetVariablesResult(
                request.projectName(), tid(thread), fid(frame),
                lineNumber, frameName, sourcePath, charStart, charEnd, variables
            );
        } catch (DebugException e) {
            LOG.log(Level.WARNING, "getVariables failed", e);
            return new GetVariablesResult(request.projectName(), null, null, 0, null, null, -1, -1, List.of());
        }
    }

    public GetCallStackResult getCallStack(GetCallStackRequest request) {
        if (request.projectName() == null)
            return new GetCallStackResult(null, null, List.of());

        List<ThreadCallStack> allThreads = new ArrayList<>();
        String activeThreadId = null;

        for (ILaunch launch : getLaunches()) {
            if (!matchesProject(launch, request.projectName())) continue;
            for (IDebugTarget target : launch.getDebugTargets()) {
                try {
                    for (IThread thread : target.getThreads()) {
                        String tid = tid(thread);
                        String tname = thread.getName();
                        boolean suspended = thread.isSuspended();
                        if (suspended && activeThreadId == null) activeThreadId = tid;

                        if (request.threadId() != null && !request.threadId().equals(tid) && !request.threadId().equals(tname))
                            continue;

                        List<StackFrameInfo> frames = new ArrayList<>();
                        if (suspended) {
                            try {
                                for (IStackFrame sf : thread.getStackFrames()) {
                                    frames.add(new StackFrameInfo(
                                        fid(sf), sf.getName(), sf.getLineNumber(),
                                        sf.getCharStart(), sf.getCharEnd(),
                                        sf.getVariables().length
                                    ));
                                }
                            } catch (DebugException e) { /* skip */ }
                        }
                        allThreads.add(new ThreadCallStack(tid, tname, suspended ? "suspended" : "running", frames));
                    }
                } catch (DebugException e) { /* skip target */ }
            }
        }
        return new GetCallStackResult(request.projectName(), activeThreadId, allThreads);
    }

    // ── Session Management ─────────────────────────────────────────────────

    public ListSessionsResult listSessions(ListSessionsRequest request) {
        List<DebugSessionInfo> sessions = new ArrayList<>();
        for (ILaunch launch : getLaunches()) {
            String project = getLaunchProjectName(launch);
            if (request.projectName() != null && !request.projectName().equals(project)) continue;

            String sessionId = getLaunchSessionId(launch);
            int targetCount = launch.getDebugTargets().length;
            String state = "running";
            String activeTid = null;
            for (IDebugTarget target : launch.getDebugTargets()) {
                try {
                    for (IThread thread : target.getThreads()) {
                        if (thread.isSuspended()) {
                            state = "suspended";
                            if (activeTid == null) activeTid = tid(thread);
                        }
                    }
                } catch (DebugException e) { /* skip */ }
            }
            sessions.add(new DebugSessionInfo(sessionId, project, state, targetCount, activeTid));
        }
        return new ListSessionsResult(sessions);
    }

    // ── Status ─────────────────────────────────────────────────────────────

    public DebugStatusResult debugStatus(DebugStatusRequest request) {
        ILaunch[] launches = getLaunches();
        int launchCount = 0, targetCount = 0;
        boolean suspended = false;
        String activeThreadId = null;

        for (ILaunch launch : launches) {
            if (request.projectName() != null && !matchesProject(launch, request.projectName())) continue;
            launchCount++;
            for (IDebugTarget target : launch.getDebugTargets()) {
                targetCount++;
                try {
                    for (IThread thread : target.getThreads()) {
                        if (thread.isSuspended()) {
                            suspended = true;
                            if (activeThreadId == null) activeThreadId = tid(thread);
                        }
                    }
                } catch (DebugException e) { /* skip */ }
            }
        }

        int breakpointCount = 0;
        try {
            breakpointCount = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints().length;
        } catch (Exception e) { /* skip */ }

        String state = suspended ? "suspended" : (targetCount > 0 ? "running" : "inactive");
        return new DebugStatusResult(
            request.projectName(), state, suspended,
            launchCount, targetCount, breakpointCount,
            activeThreadId, "Debug status collected"
        );
    }

    // ── Wait for Break (event-driven) ──────────────────────────────────────

    public WaitForBreakResult waitForBreak(WaitForBreakRequest request) {
        if (request.projectName() == null)
            return new WaitForBreakResult(null, false, null, "error", 0, false);

        long timeoutMs = request.timeoutMs() != null ? request.timeoutMs() : 30000L;
        long started = System.currentTimeMillis();

        // First check: maybe already suspended
        IThread existing = findThread(request.projectName(), null, true);
        if (existing != null) {
            return new WaitForBreakResult(request.projectName(), true, tid(existing),
                "suspended", System.currentTimeMillis() - started, false);
        }

        // Event-driven wait
        IThread thread = breakListener.waitForSuspend(timeoutMs);
        long elapsed = System.currentTimeMillis() - started;

        if (thread != null) {
            // Verify it matches our project
            IThread match = findThread(request.projectName(), null, true);
            if (match != null) {
                return new WaitForBreakResult(request.projectName(), true, tid(match),
                    "suspended", elapsed, false);
            }
        }

        return new WaitForBreakResult(request.projectName(), false, null,
            "timeout", elapsed, true);
    }

    // ── Navigation Helpers ─────────────────────────────────────────────────

    private ILaunch[] getLaunches() {
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        return mgr != null ? mgr.getLaunches() : new ILaunch[0];
    }

    private boolean matchesProject(ILaunch launch, String projectName) {
        try {
            String configured = launch.getLaunchConfiguration()
                .getAttribute(ATTR_PROJECT_NAME, "");
            return projectName.equals(configured);
        } catch (Exception e) {
            return false;
        }
    }

    IThread findThread(String projectName, String threadId, boolean requireSuspended) {
        for (ILaunch launch : getLaunches()) {
            if (!matchesProject(launch, projectName)) continue;
            for (IDebugTarget target : launch.getDebugTargets()) {
                try {
                    for (IThread thread : target.getThreads()) {
                        if (requireSuspended && !thread.isSuspended()) continue;
                        if (threadId == null) return thread;
                        String tid = tid(thread);
                        if (threadId.equals(tid) || threadId.equals(thread.getName())) return thread;
                    }
                } catch (DebugException e) { /* skip */ }
            }
        }
        return null;
    }

    private IStackFrame findFrameInThread(IThread thread, String frameId) {
        try {
            for (IStackFrame frame : thread.getStackFrames()) {
                if (frameId == null) return frame;
                if (frameId.equals(fid(frame)) || frameId.equals(frame.getName())) return frame;
            }
        } catch (DebugException e) { /* skip */ }
        return null;
    }

    private String getLaunchProjectName(ILaunch launch) {
        try {
            return launch.getLaunchConfiguration().getAttribute(ATTR_PROJECT_NAME, "");
        } catch (Exception e) {
            return "";
        }
    }

    String getLaunchSessionId(ILaunch launch) {
        return "session-" + Integer.toHexString(System.identityHashCode(launch));
    }

    private static String tid(IThread thread) {
        return thread.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(thread));
    }

    private static String fid(IStackFrame frame) {
        return frame.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(frame));
    }

    private static StepResult error(String msg, StepRequest req) {
        return new StepResult(req.projectName(), null, req.kind(), "error", msg);
    }
}
