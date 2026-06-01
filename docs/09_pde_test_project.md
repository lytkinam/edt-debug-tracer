# 09. PDE Test Project

## Structure

```
plugin.tests/
  META-INF/MANIFEST.MF
  .project
  .classpath
  src/test/java/com/tracer/edt/tests/
    LoopCollapserTest.java
    McpApiTest.java
    TraceRepositoryTest.java
```

## Import

1. File → Import → Existing Projects into Workspace.
2. Select `plugin.tests/` folder.
3. Finish.

## Run

1. Right-click `plugin.tests` → Run As → JUnit Plug-in Test.
2. Make sure Target Platform is set to EDT (see docs/06_dev_environment.md).

## What is tested

| Class | Type | What |
|---|---|---|
| `LoopCollapserTest` | Unit (no Eclipse) | Collapse algorithm, edge cases |
| `TraceRepositoryTest` | Integration | SQLite read/write via temp file |
| `McpApiTest` | Smoke HTTP | All MCP endpoints, skipped if server not running |
