-- The role the idempotency module's code uses (idempotency design, section 5).
-- It may only look a key up and insert one: a stored response is never
-- changed, and only the removal job, under a role of its own, deletes keys.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'finaxis_idempotency') THEN
        CREATE ROLE finaxis_idempotency NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA idempotency TO finaxis_idempotency;
GRANT SELECT, INSERT ON idempotency.keys TO finaxis_idempotency;

-- The service switches to it with SET ROLE, as to the other module roles (V15).
GRANT finaxis_idempotency TO finaxis_app WITH INHERIT FALSE, SET TRUE;
