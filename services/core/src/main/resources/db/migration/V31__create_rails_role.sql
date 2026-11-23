-- The role the rails module's code uses (billing design, sections 7.2 and
-- 11). It may record credits in the inbox: an insert that skips a credit
-- already recorded reads the EndToEndId it conflicts on, so it needs SELECT as
-- well. No role may delete a credit: the inbox is the evidence of what the
-- core accepted.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_rails') THEN
        CREATE ROLE axion_rails NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA rails TO axion_rails;
GRANT SELECT, INSERT ON rails.pix_credits TO axion_rails;

-- The service switches to it with SET ROLE, as to the other module roles (V15).
GRANT axion_rails TO axion_app WITH INHERIT FALSE, SET TRUE;
