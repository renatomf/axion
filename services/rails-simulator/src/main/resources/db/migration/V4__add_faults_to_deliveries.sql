-- The chaos faults applied to each delivery, in the order applied (rails
-- simulator design, section 7.1), so a test asserts from the simulator's
-- side what the core was put through. A JSON array, appended to with each
-- attempt's faults in the transaction that records the attempt.
ALTER TABLE deliveries
    ADD COLUMN faults jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(faults) = 'array');
