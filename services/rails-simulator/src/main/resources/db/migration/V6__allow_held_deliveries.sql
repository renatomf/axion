-- A delivery a chaos `reorder` rule holds back is `held`: owed to the core but
-- not due, so it has no next attempt until the rule releases it (rails
-- simulator design, section 7.2). The rule that holds it is in its faults.
ALTER TABLE deliveries DROP CONSTRAINT deliveries_status_check;
ALTER TABLE deliveries
    ADD CONSTRAINT deliveries_status_check CHECK (status IN ('pending', 'held', 'delivered', 'refused', 'failed'));

-- The dispatcher's look for held deliveries whose rule is gone.
CREATE INDEX deliveries_held ON deliveries (created_at) WHERE status = 'held';
