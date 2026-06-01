package com.tracer.edt.db;

import com.tracer.edt.core.AsyncTraceWriter.QueuedTraceEntry;
import com.tracer.edt.core.CollapsedTraceEntry;
import com.tracer.edt.core.TraceEntry;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * All SQLite access for EDT Debug Tracer.
 * Single connection, WAL mode for concurrent reads.
 */
public class TraceRepository implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TraceRepository.class.getName());
    private final Connection conn;

    public TraceRepository(Path dbPath) throws SQLException {
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        conn = DriverManager.getConnection(url);
    }

    public void init() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    started_at INTEGER NOT NULL,
                    stopped_at INTEGER,
                    status     TEXT DEFAULT 'active'
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS raw_trace (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    procedure  TEXT,
                    line       INTEGER,
                    module     TEXT,
                    thread_id  INTEGER,
                    ts         INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS clean_trace (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id   TEXT NOT NULL,
                    procedure    TEXT,
                    line         INTEGER,
                    module       TEXT,
                    kind         TEXT,
                    repeat_count INTEGER DEFAULT 1,
                    pattern_len  INTEGER,
                    ts           INTEGER NOT NULL
                )""");
        }
    }

    public void startSession(String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR REPLACE INTO sessions(session_id, started_at, status) VALUES(?,?,?)")) {
            ps.setString(1, sessionId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, "active");
            ps.executeUpdate();
        }
    }

    public void stopSession(String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE sessions SET stopped_at=?, status='stopped' WHERE session_id=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
    }

    public void insertRawBatch(List<QueuedTraceEntry> batch) throws SQLException {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO raw_trace(session_id,procedure,line,module,thread_id,ts) VALUES(?,?,?,?,?,?)")) {
            for (QueuedTraceEntry q : batch) {
                ps.setString(1, q.sessionId);
                ps.setString(2, q.entry.getProcedure());
                ps.setInt(3, q.entry.getLine());
                ps.setString(4, q.entry.getModule());
                ps.setLong(5, q.entry.getThreadId());
                ps.setLong(6, q.entry.getTs());
                ps.addBatch();
            }
            ps.executeBatch();
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public List<TraceEntry> loadRaw(String sessionId) throws SQLException {
        List<TraceEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT procedure, line, module, thread_id, ts FROM raw_trace WHERE session_id=? ORDER BY id")) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new TraceEntry(
                    rs.getString(1), rs.getInt(2), rs.getString(3),
                    rs.getLong(4), rs.getLong(5)
                ));
            }
        }
        return list;
    }

    public void replaceClean(String sessionId, List<CollapsedTraceEntry> clean) throws SQLException {
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM clean_trace WHERE session_id=?")) {
                del.setString(1, sessionId);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO clean_trace(session_id,procedure,line,module,kind,repeat_count,pattern_len,ts)"
                + " VALUES(?,?,?,?,?,?,?,?)")) {
                for (CollapsedTraceEntry e : clean) {
                    ins.setString(1, sessionId);
                    ins.setString(2, e.getProcedure());
                    ins.setInt(3, e.getLine());
                    ins.setString(4, e.getModule());
                    ins.setString(5, e.getKind().name().toLowerCase());
                    ins.setInt(6, e.getRepeatCount());
                    ins.setInt(7, e.getPatternLen());
                    ins.setLong(8, e.getTs());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public String traceAsJson(String sessionId, String type) throws SQLException {
        String table = "clean".equals(type) ? "clean_trace" : "raw_trace";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        String sql = "raw_trace".equals(table)
            ? "SELECT procedure,line,module,thread_id,ts FROM raw_trace WHERE session_id=? ORDER BY id"
            : "SELECT procedure,line,module,kind,repeat_count,pattern_len,ts FROM clean_trace WHERE session_id=? ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (!first) sb.append(",");
                first = false;
                if ("raw_trace".equals(table)) {
                    new TraceEntry(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5)).toJson();
                    sb.append(new TraceEntry(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5)).toJson());
                } else {
                    sb.append(new CollapsedTraceEntry(
                        CollapsedTraceEntry.Kind.valueOf(rs.getString(4).toUpperCase()),
                        rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getInt(5), rs.getInt(6), rs.getLong(7)).toJson());
                }
            }
        }
        return sb.append("]").toString();
    }

    public int countRaw(String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COUNT(*) FROM raw_trace WHERE session_id=?")) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException e) { LOG.warning("Error closing DB: " + e.getMessage()); }
    }
}
