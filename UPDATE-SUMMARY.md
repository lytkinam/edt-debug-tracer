# Update Summary v2

## What changed

- Replaced in-memory `StepLogBuffer` with async SQLite pipeline
- Added `AsyncTraceWriter` (queue + background thread)
- Added `TraceRepository` (raw_trace + clean_trace tables)
- Added `TraceSessionManager`
- Added `LoopCollapser` (repeat collapse + pattern detection)
- Added `CollapsedTraceEntry` with JSON serialization
- Extended `McpHttpServer` with `/mcp/postprocess` and `/mcp/trace`
- Added `plugin.tests/` PDE test project with 3 Java test classes
- Added `docs/09_pde_test_project.md`
- Added `docs/10_lib_checklist.md`

## sqlite-jdbc dependency

Download: https://github.com/xerial/sqlite-jdbc/releases/download/3.53.1.0/sqlite-jdbc-3.53.1.0.jar
Place as: plugin/lib/sqlite-jdbc.jar AND plugin.tests/lib/sqlite-jdbc.jar
