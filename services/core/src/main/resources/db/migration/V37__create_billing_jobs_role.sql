-- The role of the job that expires charges across tenants (billing design,
-- section 9). It may read only the columns it selects by and change only the
-- status and its time, and its policies let it change active charges only,
-- and only to EXPIRED: even with a bug in its query, it can neither touch a
-- paid charge nor move a charge anywhere but to EXPIRED. No role has
-- BYPASSRLS.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_billing_jobs') THEN
        CREATE ROLE axion_billing_jobs NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA billing TO axion_billing_jobs;
GRANT SELECT (id, status, expires_at) ON billing.charges TO axion_billing_jobs;
GRANT UPDATE (status, updated_at) ON billing.charges TO axion_billing_jobs;

-- An UPDATE that reads the table checks the new row against the SELECT
-- policies too, so the role must see the charges it has just expired.
CREATE POLICY unpaid_charges_visible ON billing.charges
    FOR SELECT TO axion_billing_jobs
    USING (status IN ('ACTIVE', 'EXPIRED'));

CREATE POLICY active_charges_expirable ON billing.charges
    FOR UPDATE TO axion_billing_jobs
    USING (status = 'ACTIVE')
    WITH CHECK (status = 'EXPIRED');

-- The service switches to it with SET ROLE, as to a module role (V15).
GRANT axion_billing_jobs TO axion_app WITH INHERIT FALSE, SET TRUE;
