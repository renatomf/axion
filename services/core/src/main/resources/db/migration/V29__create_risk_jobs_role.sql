-- The role of the job that removes old risk windows across tenants (billing
-- design, sections 6 and 11). It may only read and delete windows, and its
-- policies show it windows that started more than 7 days ago only: even with
-- a bug in its query, it can neither read a window still counting nor reset
-- a tenant's limits by removing it. No role has BYPASSRLS.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'finaxis_risk_jobs') THEN
        CREATE ROLE finaxis_risk_jobs NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA risk TO finaxis_risk_jobs;
GRANT SELECT, DELETE ON risk.charge_volume TO finaxis_risk_jobs;

-- A DELETE with a condition reads the rows it deletes, so it needs both.
CREATE POLICY old_windows_visible ON risk.charge_volume
    FOR SELECT TO finaxis_risk_jobs
    USING (window_start < now() - interval '7 days');

CREATE POLICY old_windows_removable ON risk.charge_volume
    FOR DELETE TO finaxis_risk_jobs
    USING (window_start < now() - interval '7 days');

-- The service switches to it with SET ROLE, as to a module role (V15).
GRANT finaxis_risk_jobs TO finaxis_app WITH INHERIT FALSE, SET TRUE;
