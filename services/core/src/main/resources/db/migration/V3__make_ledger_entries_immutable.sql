-- I2: entries and postings are immutable (ledger design, section 4). The
-- ledger role is never granted UPDATE, DELETE or TRUNCATE on them; these
-- triggers reject those statements for every other role too, including the
-- owner. A mistake is corrected only by a reversal entry.
CREATE FUNCTION ledger.reject_change() RETURNS trigger
    LANGUAGE plpgsql
    -- An empty search path: the function resolves nothing a caller could
    -- shadow with an object of the same name.
    SET search_path = ''
AS $$
BEGIN
    RAISE EXCEPTION 'ledger.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation',
              HINT = 'Correct a mistake with a reversal entry.';
END;
$$;

CREATE TRIGGER entries_are_immutable
    BEFORE UPDATE OR DELETE ON ledger.entries
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_change();

CREATE TRIGGER entries_are_not_truncated
    BEFORE TRUNCATE ON ledger.entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_change();

CREATE TRIGGER postings_are_immutable
    BEFORE UPDATE OR DELETE ON ledger.postings
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_change();

CREATE TRIGGER postings_are_not_truncated
    BEFORE TRUNCATE ON ledger.postings
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_change();
