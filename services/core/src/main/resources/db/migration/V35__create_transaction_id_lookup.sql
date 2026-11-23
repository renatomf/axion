-- A credit notification names no tenant, only the charge's transaction ID, so
-- settlement finds the tenant before it can bind one (billing design, section
-- 7.3). The lookup goes through a SECURITY DEFINER function, which runs with
-- the privileges of its owner, axion_billing_lookup: a role that cannot log
-- in, may read only the two columns it needs, and whose policy on the charges
-- applies to it alone, as for the API key lookup (V12).
--
-- The function answers for the one charge whose transaction ID it is given:
-- it cannot list charges, only resolve an ID the rails sent.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_billing_lookup') THEN
        CREATE ROLE axion_billing_lookup NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA billing TO axion_billing_lookup;
GRANT SELECT (tenant_id, transaction_id) ON billing.charges TO axion_billing_lookup;

CREATE POLICY transaction_id_lookup ON billing.charges
    FOR SELECT TO axion_billing_lookup
    USING (true);

-- Null for a transaction ID no charge has. An empty search_path makes every
-- name below resolve to its schema, so a caller's objects can never stand in
-- for them.
CREATE FUNCTION billing.find_charge_tenant(transaction_id text)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = ''
BEGIN ATOMIC
    SELECT c.tenant_id
      FROM billing.charges c
     WHERE c.transaction_id = find_charge_tenant.transaction_id;
END;

ALTER FUNCTION billing.find_charge_tenant(text) OWNER TO axion_billing_lookup;

-- A function may be called by anyone unless revoked from PUBLIC.
REVOKE ALL ON FUNCTION billing.find_charge_tenant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing.find_charge_tenant(text) TO axion_billing;
