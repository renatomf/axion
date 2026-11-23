-- The role the risk module's code uses (billing design, section 11). It may
-- create a window's row and add charges to it; only the removal job, under a
-- role of its own, deletes windows.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_risk') THEN
        CREATE ROLE axion_risk NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA risk TO axion_risk;
GRANT SELECT, INSERT, UPDATE ON risk.charge_volume TO axion_risk;

-- The service switches to it with SET ROLE, as to the other module roles (V15).
GRANT axion_risk TO axion_app WITH INHERIT FALSE, SET TRUE;
