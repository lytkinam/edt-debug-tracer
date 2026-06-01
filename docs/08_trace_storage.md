# 08. Trace Storage (SQLite)

## Database location

Default: `~/.edt-debug-tracer/trace.db`
Configurable via system property: `-Dedt.tracer.db.path=/path/to/trace.db`

## Schema

```sql
CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    started_at INTEGER NOT NULL,
    stopped_at INTEGER,
    status     TEXT DEFAULT 'active'
);

CREATE TABLE IF NOT EXISTS raw_trace (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    procedure  TEXT,
    line       INTEGER,
    module     TEXT,
    thread_id  INTEGER,
    ts         INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS clean_trace (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   TEXT NOT NULL,
    procedure    TEXT,
    line         INTEGER,
    module       TEXT,
    kind         TEXT,  -- 'step' | 'repeat' | 'loop'
    repeat_count INTEGER DEFAULT 1,
    pattern_len  INTEGER,
    ts           INTEGER NOT NULL
);
```

## Post-processing pipeline

1. Load `raw_trace` for session.
2. Run `LoopCollapser.collapse(List<TraceEntry>)`.
3. Replace `clean_trace` for session with result.

## JSON output format (clean)

```json
[
  {"kind":"step","procedure":"ОбщийМодуль","line":5,"module":"main","ts":1717123456789},
  {"kind":"repeat","procedure":"Цикл","line":10,"repeat_count":47,"ts":1717123456901},
  {"kind":"loop","pattern_len":3,"repeat_count":12,"ts":1717123457000}
]
```
