# Import Checklist

1. Eclipse IDE for RCP Developers (2024-12 or later)
2. Import `plugin/` as Plug-in Project
3. Import `plugin.tests/` as Plug-in Project
4. Set Target Platform to installed EDT (see docs/06_dev_environment.md)
5. Place `sqlite-jdbc-3.53.1.0.jar` as `plugin/lib/sqlite-jdbc.jar` AND `plugin.tests/lib/sqlite-jdbc.jar`
6. Project → Clean → Build All
7. Run As → JUnit Plug-in Test (select plugin.tests)
