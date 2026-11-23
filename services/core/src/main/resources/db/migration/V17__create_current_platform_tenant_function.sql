-- The platform tenant of the environment the transaction acts in, which the
-- ledger's policies admit beside the tenant (tenancy design, section 5.5). The
-- service sets axion.platform_tenant_id with axion.tenant_id, as
-- shared.current_tenant() reads it, and null in the same cases.
CREATE FUNCTION shared.current_platform_tenant() RETURNS uuid
    LANGUAGE sql
    STABLE
    RETURN nullif(current_setting('axion.platform_tenant_id', true), '')::uuid;

-- The ledger is the first module with tenant policies to need either function.
REVOKE ALL ON FUNCTION shared.current_platform_tenant() FROM PUBLIC;
GRANT USAGE ON SCHEMA shared TO axion_ledger;
GRANT EXECUTE ON FUNCTION shared.current_tenant(), shared.current_platform_tenant() TO axion_ledger;
