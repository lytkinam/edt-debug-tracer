package plugin17.test;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQLite storage for tracer data (P2.1).
 * Manages sessions, steps, and analysis tables.
 * Uses batch inserts for performance.
 */
public class TracerStorage {

    private final String dbPath;
    private Connection conn;
    private PreparedStatement insertStepStmt;
    private final BlockingQueue<Object[]> stepQueue = new LinkedBlockingQueue<>(100_000);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread writerThread;

    // Config
    private int batchSize = 50;
    private long batchTimeoutMs = 200;
    private boolean walMode = true;
    private String journalMode = "WAL";
    private String synchronous = "NORMAL";
    private int cacheSize = 8000;

    public TracerStorage(String dbPath) {
        this.dbPath = dbPath;
    }

    public void setBatchSize(int size) { this.batchSize = size; }
    public void setBatchTimeoutMs(long ms) { this.batchTimeoutMs = ms; }
    public void setWalMode(boolean enabled) { this.walMode = enabled; }
    public void setJournalMode(String mode) { this.journalMode = mode; }
    public void setSynchronous(String mode) { this.synchronous = mode; }
    public void setCacheSize(int pages) { this.cacheSize = pages; }

    /**
     * Open database connection and create schema.
     */
    public void open() throws SQLException {
        // Explicitly load SQLite JDBC driver (OSGi classloading)
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found: " + e.getMessage());
        }
        String url = "jdbc:sqlite:" + dbPath;
        conn = DriverManager.getConnection(url);

        // Apply PRAGMAs
        try (Statement stmt = conn.createStatement()) {
            if (walMode) {
                stmt.execute("PRAGMA journal_mode=" + journalMode);
            }
            stmt.execute("PRAGMA synchronous=" + synchronous);
            stmt.execute("PRAGMA cache_size=" + cacheSize);
            stmt.execute("PRAGMA temp_store=MEMORY");
        }

        initSchema();
        prepareStatements();
    }

    /**
     * Create all tables if they don't exist.
     */
    private void initSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Sessions table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id      TEXT UNIQUE NOT NULL,
                    project_name    TEXT,
                    workspace_path  TEXT,
                    launch_config   TEXT,
                    debug_target    TEXT,
                    started_at      INTEGER NOT NULL,
                    stopped_at      INTEGER,
                    status          TEXT DEFAULT 'active',
                    total_steps     INTEGER DEFAULT 0,
                    auto_steps      INTEGER DEFAULT 0,
                    config_json     TEXT
                )
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_project ON sessions(project_name)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_started ON sessions(started_at)");

            // Steps table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS steps (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id      TEXT NOT NULL REFERENCES sessions(session_id),
                    seq             INTEGER NOT NULL,
                    ts              INTEGER NOT NULL,
                    procedure_name  TEXT NOT NULL,
                    line_number     INTEGER NOT NULL,
                    module_path     TEXT,
                    char_start      INTEGER DEFAULT -1,
                    char_end        INTEGER DEFAULT -1,
                    thread_id       INTEGER,
                    thread_name     TEXT,
                    stack_depth     INTEGER DEFAULT 0,
                    parent_seq      INTEGER,
                    stack_json      TEXT,
                    variables_json  TEXT,
                    UNIQUE(session_id, seq)
                )
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_steps_session ON steps(session_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_steps_seq ON steps(session_id, seq)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_steps_procedure ON steps(session_id, procedure_name)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_steps_parent ON steps(session_id, parent_seq)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_steps_depth ON steps(session_id, stack_depth)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_steps_module ON steps(session_id, module_path)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_steps_thread ON steps(session_id, thread_id)");

            // Analysis table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS analysis (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id      TEXT NOT NULL REFERENCES sessions(session_id),
                    analysis_type   TEXT NOT NULL,
                    result_json     TEXT,
                    created_at      INTEGER DEFAULT (strftime('%s','now') * 1000)
                )
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_analysis_session ON analysis(session_id, analysis_type)");

            // Call edges table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS call_edges (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id      TEXT NOT NULL REFERENCES sessions(session_id),
                    caller_module   TEXT,
                    caller_proc     TEXT,
                    callee_module   TEXT,
                    callee_proc     TEXT,
                    call_count      INTEGER DEFAULT 1,
                    avg_depth       REAL,
                    first_seq       INTEGER,
                    last_seq        INTEGER
                )
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_session ON call_edges(session_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_caller ON call_edges(session_id, caller_proc)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_callee ON call_edges(session_id, callee_proc)");
        }
    }

    private void prepareStatements() throws SQLException {
        insertStepStmt = conn.prepareStatement("""
            INSERT OR REPLACE INTO steps (
                session_id, seq, ts, procedure_name, line_number, module_path,
                char_start, char_end, thread_id, thread_name,
                stack_depth, parent_seq, stack_json, variables_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """);
    }

    /**
     * Create a new session record. Returns session_id.
     */
    public String createSession(String projectName, String workspacePath,
                                 String launchConfig, String debugTarget,
                                 String configJson) throws SQLException {
        String sessionId = "s-" + System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sessions (session_id, project_name, workspace_path, launch_config, debug_target, started_at, config_json) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, projectName);
            ps.setString(3, workspacePath);
            ps.setString(4, launchConfig);
            ps.setString(5, debugTarget);
            ps.setLong(6, System.currentTimeMillis());
            ps.setString(7, configJson);
            ps.executeUpdate();
        }
        return sessionId;
    }

    /**
     * Queue a step for batch insert.
     */
    public void queueStep(String sessionId, int seq, long ts,
                           String procedure, int line, String module,
                           int charStart, int charEnd,
                           int threadId, String threadName,
                           int stackDepth, int parentSeq,
                           String stackJson, String variablesJson) {
        Object[] row = new Object[]{
            sessionId, seq, ts, procedure, line, module,
            charStart, charEnd, threadId, threadName,
            stackDepth, parentSeq, stackJson, variablesJson
        };
        stepQueue.offer(row);
    }

    /**
     * Update session status and counters.
     */
    public void updateSession(String sessionId, String status, int totalSteps, int autoSteps) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sessions SET status=?, stopped_at=?, total_steps=?, auto_steps=? WHERE session_id=?")) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, totalSteps);
            ps.setInt(4, autoSteps);
            ps.setString(5, sessionId);
            ps.executeUpdate();
        }
    }

    /**
     * Start the background writer thread.
     */
    public void startWriter() {
        running.set(true);
        writerThread = new Thread(this::writerLoop, "tracer-sqlite-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /**
     * Stop the writer thread and flush remaining.
     */
    public void stopWriter() {
        running.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
            try { writerThread.join(3000); } catch (InterruptedException e) { /* skip */ }
        }
        // Final flush
        flushBatch();
    }

    private void writerLoop() {
        List<Object[]> batch = new ArrayList<>(batchSize);
        while (running.get() || !stepQueue.isEmpty()) {
            try {
                Object[] head = stepQueue.poll(batchTimeoutMs, TimeUnit.MILLISECONDS);
                if (head != null) {
                    batch.add(head);
                    stepQueue.drainTo(batch, batchSize - 1);
                    if (batch.size() >= batchSize) {
                        executeBatch(batch);
                        batch.clear();
                    }
                } else if (!batch.isEmpty()) {
                    // Timeout: flush partial batch
                    executeBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[tracer] SQLite writer error: " + e.getMessage());
            }
        }
        // Flush remaining
        if (!batch.isEmpty()) {
            try { executeBatch(batch); } catch (Exception e) { /* skip */ }
        }
    }

    private synchronized void executeBatch(List<Object[]> batch) throws SQLException {
        if (batch.isEmpty() || conn == null || conn.isClosed()) return;
        conn.setAutoCommit(false);
        try {
            for (Object[] row : batch) {
                insertStepStmt.setString(1, (String) row[0]);   // session_id
                insertStepStmt.setInt(2, (int) row[1]);         // seq
                insertStepStmt.setLong(3, (long) row[2]);       // ts
                insertStepStmt.setString(4, (String) row[3]);   // procedure
                insertStepStmt.setInt(5, (int) row[4]);         // line
                insertStepStmt.setString(6, (String) row[5]);   // module
                insertStepStmt.setInt(7, (int) row[6]);         // charStart
                insertStepStmt.setInt(8, (int) row[7]);         // charEnd
                insertStepStmt.setInt(9, (int) row[8]);         // threadId
                insertStepStmt.setString(10, (String) row[9]);  // threadName
                insertStepStmt.setInt(11, (int) row[10]);       // stackDepth
                insertStepStmt.setInt(12, (int) row[11]);       // parentSeq
                insertStepStmt.setString(13, (String) row[12]); // stackJson
                insertStepStmt.setString(14, (String) row[13]); // variablesJson
                insertStepStmt.addBatch();
            }
            insertStepStmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void flushBatch() {
        List<Object[]> remaining = new ArrayList<>();
        stepQueue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            try { executeBatch(remaining); } catch (Exception e) {
                System.err.println("[tracer] SQLite flush error: " + e.getMessage());
            }
        }
    }

    /**
     * Close the database connection.
     */
    public void close() {
        stopWriter();
        try {
            if (insertStepStmt != null) insertStepStmt.close();
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            System.err.println("[tracer] SQLite close error: " + e.getMessage());
        }
    }

    /**
     * Check if database is open.
     */
    public boolean isOpen() {
        try { return conn != null && !conn.isClosed(); } catch (Exception e) { return false; }
    }

    /**
     * Get session count (for health/status).
     */
    public int getSessionCount() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) { return -1; }
    }

    /**
     * Get last session info as JSON.
     */
    public String getLastSessionJson() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                "SELECT session_id, project_name, total_steps, auto_steps, status, started_at, stopped_at " +
                "FROM sessions ORDER BY started_at DESC LIMIT 1")) {
            if (rs.next()) {
                return "{\"session_id\":\"" + rs.getString("session_id")
                    + "\",\"project_name\":\"" + esc(rs.getString("project_name"))
                    + "\",\"total_steps\":" + rs.getInt("total_steps")
                    + ",\"auto_steps\":" + rs.getInt("auto_steps")
                    + ",\"status\":\"" + rs.getString("status")
                    + "\",\"started_at\":" + rs.getLong("started_at")
                    + ",\"stopped_at\":" + rs.getLong("stopped_at") + "}";
            }
            return "{\"error\":\"no sessions\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * Get steps from a session as StepEntry list (for breakpoint creation).
     */
    public java.util.List<StepEntry> getSessionSteps(String sessionId) {
        java.util.List<StepEntry> result = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT procedure_name, line_number, module_path, thread_name, thread_id, ts, " +
                "char_start, char_end, stack_depth, parent_seq, stack_json, variables_json " +
                "FROM steps WHERE session_id = ? ORDER BY seq")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new StepEntry(
                        rs.getString("procedure_name"),
                        rs.getInt("line_number"),
                        rs.getString("module_path"),
                        rs.getString("thread_name"),
                        rs.getInt("thread_id"),
                        rs.getLong("ts"),
                        rs.getInt("char_start"),
                        rs.getInt("char_end"),
                        rs.getInt("stack_depth"),
                        rs.getInt("parent_seq"),
                        rs.getString("stack_json"),
                        rs.getString("variables_json")
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("[tracer] getSessionSteps error: " + e.getMessage());
        }
        return result;
    }

    /**
     * Get the last session ID.
     */
    public String getLastSessionId() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                "SELECT session_id FROM sessions ORDER BY started_at DESC LIMIT 1")) {
            return rs.next() ? rs.getString("session_id") : null;
        } catch (Exception e) { return null; }
    }

    /**
     * List all sessions as JSON array.
     */
    public String listSessionsJson() {
        StringBuilder sb = new StringBuilder("[");
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                "SELECT session_id, project_name, total_steps, auto_steps, status, started_at, stopped_at " +
                "FROM sessions ORDER BY started_at DESC")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"session_id\":\"").append(rs.getString("session_id"))
                  .append("\",\"project_name\":\"").append(esc(rs.getString("project_name")))
                  .append("\",\"total_steps\":").append(rs.getInt("total_steps"))
                  .append(",\"auto_steps\":").append(rs.getInt("auto_steps"))
                  .append(",\"status\":\"").append(rs.getString("status"))
                  .append("\",\"started_at\":").append(rs.getLong("started_at"))
                  .append(",\"stopped_at\":").append(rs.getLong("stopped_at"))
                  .append("}");
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Get steps for a session as JSON array.
     */
    public String getSessionStepsJson(String sessionId) {
        StringBuilder sb = new StringBuilder("[");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT seq, procedure_name, line_number, module_path, thread_name, thread_id, ts, " +
                "char_start, char_end, stack_depth, parent_seq, stack_json, variables_json " +
                "FROM steps WHERE session_id = ? ORDER BY seq")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"seq\":").append(rs.getInt("seq"))
                      .append(",\"procedure\":\"").append(esc(rs.getString("procedure_name")))
                      .append("\",\"line\":").append(rs.getInt("line_number"))
                      .append(",\"module\":\"").append(esc(rs.getString("module_path")))
                      .append("\",\"thread_name\":\"").append(esc(rs.getString("thread_name")))
                      .append("\",\"thread_id\":").append(rs.getInt("thread_id"))
                      .append(",\"ts\":").append(rs.getLong("ts"))
                      .append(",\"char_start\":").append(rs.getInt("char_start"))
                      .append(",\"char_end\":").append(rs.getInt("char_end"))
                      .append(",\"stack_depth\":").append(rs.getInt("stack_depth"))
                      .append(",\"parent_seq\":").append(rs.getInt("parent_seq"));
                    String stackJson = rs.getString("stack_json");
                    if (stackJson != null) sb.append(",\"stack\":").append(stackJson);
                    String varsJson = rs.getString("variables_json");
                    if (varsJson != null) sb.append(",\"variables\":").append(varsJson);
                    sb.append("}");
                }
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
        sb.append("]");
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
