-- The platform tenant of the environment the transaction acts in, which the
-- ledger's policies admit beside the tenant (tenancy design, section 5.5). The
-- service sets finaxis.platform_tenant_id with finaxis.tenant_id, as
-- shared.current_tenant() reads it, and null in the same cases.
CREATE FUNCTION shared.current_platform_tenant() RETURNS uuid
    LANGUAGE sql
    STABLE
    RETURN nullif(current_setting('finaxis.platform_tenant_id', true), '')::uuid;

-- The ledger is the first module with tenant policies to need either function.
REVOKE ALL ON FUNCTION shared.current_platform_tenant() FROM PUBLIC;
GRANT USAGE ON SCHEMA shared TO finaxis_ledger;
GRANT EXECUTE ON FUNCTION shared.current_tenant(), shared.current_platform_tenant() TO finaxis_ledger;
