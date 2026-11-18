-- I1: every entry balances per currency, with at least two postings (ledger
-- design, section 4). The application's Entry already enforces it; the
-- database repeats it at commit, so no other path can commit a broken entry.
--
-- The triggers are deferred to commit time: checked after each row, every
-- multi-leg entry would be unbalanced until its last posting is inserted. An
-- error at commit rolls back the whole transaction, including the caller's
-- business change: the ledger fails closed.

CREATE FUNCTION ledger.check_entry_balances() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
AS $$
BEGIN
    -- sum() over bigint returns numeric, so the sum cannot overflow.
    IF EXISTS (
        SELECT 1
          FROM ledger.postings
         WHERE entry_id = NEW.entry_id
         GROUP BY currency
        HAVING sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
    ) THEN
        RAISE EXCEPTION 'ledger entry % does not balance', NEW.entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

-- Runs once per inserted posting, at commit, when all of the entry's
-- postings exist.
CREATE CONSTRAINT TRIGGER postings_balance_per_entry
    AFTER INSERT ON ledger.postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balances();

CREATE FUNCTION ledger.check_entry_has_postings() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
AS $$
BEGIN
    IF (SELECT count(*) FROM ledger.postings WHERE entry_id = NEW.id) < 2 THEN
        RAISE EXCEPTION 'ledger entry % has fewer than two postings', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

-- An entry with no postings fires no posting trigger, so the entry itself is
-- checked too.
CREATE CONSTRAINT TRIGGER entries_have_postings
    AFTER INSERT ON ledger.entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_has_postings();
