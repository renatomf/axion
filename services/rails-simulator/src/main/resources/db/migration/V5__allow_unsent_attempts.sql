-- An attempt a chaos `fail` fault kept from being sent records why no answer
-- came as `not_sent` (rails simulator design, section 7.2), so the record
-- never claims a connection failed when none was tried.
ALTER TABLE delivery_attempts DROP CONSTRAINT delivery_attempts_error_check;
ALTER TABLE delivery_attempts
    ADD CONSTRAINT delivery_attempts_error_check CHECK (error IN ('timeout', 'connection_failed', 'not_sent'));
