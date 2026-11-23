-- Authentication finds a key by its hash before any tenant is known, and
-- records its use in the same transaction (tenancy design, section 5.4).
-- Both go through SECURITY DEFINER functions, which run with the privileges
-- of their owner, axion_access_lookup: a role that cannot log in and whose
-- policies on the keys table apply to it alone. Owned by the table's owner,
-- the functions would run under the forced tenant policy and find nothing.
--
-- Each acts only on the key whose hash it is given: it cannot list keys, only
-- resolve a secret the caller already holds.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_access_lookup') THEN
        CREATE ROLE axion_access_lookup NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA access TO axion_access_lookup;
GRANT SELECT ON access.api_keys TO axion_access_lookup;
GRANT UPDATE (last_used_at) ON access.api_keys TO axion_access_lookup;

-- An empty search_path makes every name below resolve to its schema, so a
-- caller's objects can never stand in for them.
CREATE FUNCTION access.find_api_key(hash bytea)
    RETURNS TABLE (
        id           uuid,
        tenant_id    uuid,
        scopes       text[],
        expires_at   timestamptz,
        revoked_at   timestamptz,
        last_used_at timestamptz)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = ''
BEGIN ATOMIC
    SELECT k.id, k.tenant_id, k.scopes, k.expires_at, k.revoked_at, k.last_used_at
      FROM access.api_keys k
     WHERE k.secret_hash = find_api_key.hash;
END;

-- True when it wrote. The condition makes concurrent requests write once and
-- keeps last_used_at from moving backwards.
CREATE FUNCTION access.record_api_key_use(hash bytea, used_at timestamptz, due_from timestamptz)
    RETURNS boolean
    LANGUAGE sql
    VOLATILE
    SECURITY DEFINER
    SET search_path = ''
BEGIN ATOMIC
    WITH used AS (
        UPDATE access.api_keys k
           SET last_used_at = record_api_key_use.used_at
         WHERE k.secret_hash = record_api_key_use.hash
           AND (k.last_used_at IS NULL OR k.last_used_at <= record_api_key_use.due_from)
        RETURNING 1)
    SELECT EXISTS (SELECT FROM used);
END;

ALTER FUNCTION access.find_api_key(bytea) OWNER TO axion_access_lookup;
ALTER FUNCTION access.record_api_key_use(bytea, timestamptz, timestamptz) OWNER TO axion_access_lookup;

-- A function may be called by anyone unless revoked from PUBLIC.
REVOKE ALL ON FUNCTION access.find_api_key(bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION access.record_api_key_use(bytea, timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.find_api_key(bytea) TO axion_access;
GRANT EXECUTE ON FUNCTION access.record_api_key_use(bytea, timestamptz, timestamptz) TO axion_access;
