-- The role the service logs in as (tenancy design, section 5.6). It holds no
-- privilege of its own: each grant below lets it switch to a module role with
-- SET ROLE, never inherit that role's privileges, so a statement outside a
-- module's API reaches no table.
--
-- The environment gives the role its login and password
-- (infra/postgres/create-login-role.sql); a migration never holds a
-- credential. Created here too, without a login, so migrating a database never
-- depends on that step.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'finaxis_app') THEN
        CREATE ROLE finaxis_app NOLOGIN NOINHERIT;
    END IF;
END;
$$;

GRANT finaxis_tenancy, finaxis_access, finaxis_ledger TO finaxis_app WITH INHERIT FALSE, SET TRUE;

-- SET ROLE does not apply a role's settings; only logging in does. The bounds
-- set on the module roles move to the role that logs in.
ALTER ROLE finaxis_app SET lock_timeout = '2s';
ALTER ROLE finaxis_app SET statement_timeout = '5s';
ALTER ROLE finaxis_app SET transaction_timeout = '30s';

ALTER ROLE finaxis_tenancy RESET ALL;
ALTER ROLE finaxis_access RESET ALL;
ALTER ROLE finaxis_ledger RESET ALL;
