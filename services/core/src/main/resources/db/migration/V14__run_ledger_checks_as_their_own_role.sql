-- The balance triggers of V4 fire at commit, after the ledger's API has
-- returned and the caller's role is back (tenancy design, section 5.6), so
-- they cannot rely on finaxis_ledger. Their functions run with the
-- privileges of their owner, finaxis_ledger_checks: a role that cannot log in
-- and may only read postings. Owned by finaxis_ledger, they could be dropped
-- by the ledger's own statements, and the triggers with them.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'finaxis_ledger_checks') THEN
        CREATE ROLE finaxis_ledger_checks NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA ledger TO finaxis_ledger_checks;
GRANT SELECT ON ledger.postings TO finaxis_ledger_checks;

-- V4 already gave both functions an empty search_path, so a caller's objects
-- can never stand in for the names they use.
ALTER FUNCTION ledger.check_entry_balances() SECURITY DEFINER;
ALTER FUNCTION ledger.check_entry_has_postings() SECURITY DEFINER;
ALTER FUNCTION ledger.check_entry_balances() OWNER TO finaxis_ledger_checks;
ALTER FUNCTION ledger.check_entry_has_postings() OWNER TO finaxis_ledger_checks;

-- A trigger calls its function whoever fires it; nobody calls these directly.
REVOKE ALL ON FUNCTION ledger.check_entry_balances() FROM PUBLIC;
REVOKE ALL ON FUNCTION ledger.check_entry_has_postings() FROM PUBLIC;
