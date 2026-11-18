-- Baseline: every module gets its own schema and database role, created by
-- its own migrations. The public schema holds only Flyway's history table,
-- so no role other than the database owner may use it.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
