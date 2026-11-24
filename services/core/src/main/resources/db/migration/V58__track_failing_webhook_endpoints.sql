-- How an endpoint has fared since its last success (events and webhooks
-- design, section 8.6): when its first attempt failed since then, and how
-- many attempts have failed since. A success clears both, and so does the
-- tenant enabling the endpoint again, which starts it afresh (section 6.3).
-- The count stands for the attempts table, which arrives with the delivery
-- log: the last 20 attempts all failed exactly when it is 20 or more.
ALTER TABLE webhooks.endpoints
    ADD COLUMN failing_since timestamptz NULL,
    ADD COLUMN consecutive_failures integer NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    ADD CONSTRAINT endpoints_failing_check CHECK ((failing_since IS NULL) = (consecutive_failures = 0));

-- The workers record each attempt's result on its endpoint, across tenants,
-- and only on an endpoint that still receives: a disabled or deleted one has
-- no attempt in flight whose result counts.
GRANT SELECT (failing_since, consecutive_failures), UPDATE (failing_since, consecutive_failures)
    ON webhooks.endpoints TO axion_webhooks_jobs;

CREATE POLICY receiving_endpoints_trackable ON webhooks.endpoints
    FOR UPDATE TO axion_webhooks_jobs
    USING (status = 'ENABLED' AND deleted_at IS NULL)
    WITH CHECK (status = 'ENABLED' AND deleted_at IS NULL);

-- The module role clears both when the tenant enables the endpoint again.
GRANT UPDATE (failing_since, consecutive_failures) ON webhooks.endpoints TO axion_webhooks;
