-- The workers that send webhooks drain the deliveries of every tenant as
-- axion_webhooks_jobs (events and webhooks design, section 8.2). They read
-- the due deliveries, the events they send and their endpoints' URLs, and
-- change only a delivery's status, lease, attempt count and next attempt.
-- No role has BYPASSRLS.
GRANT SELECT (id, endpoint_id, event_id, status, attempt_count, next_attempt_at, leased_until)
    ON webhooks.deliveries TO axion_webhooks_jobs;
GRANT UPDATE (status, attempt_count, next_attempt_at, leased_until) ON webhooks.deliveries TO axion_webhooks_jobs;
GRANT SELECT (id, body) ON webhooks.events TO axion_webhooks_jobs;
GRANT SELECT (id, url, status, deleted_at) ON webhooks.endpoints TO axion_webhooks_jobs;

CREATE POLICY deliveries_visible ON webhooks.deliveries
    FOR SELECT TO axion_webhooks_jobs
    USING (true);

-- Only a pending delivery changes, and only to pending, succeeded or dead:
-- the jobs never revive a finished delivery nor cancel one, which only its
-- endpoint's changes do.
CREATE POLICY pending_deliveries_sendable ON webhooks.deliveries
    FOR UPDATE TO axion_webhooks_jobs
    USING (status = 'PENDING')
    WITH CHECK (status IN ('PENDING', 'SUCCEEDED', 'DEAD'));

CREATE POLICY events_visible ON webhooks.events
    FOR SELECT TO axion_webhooks_jobs
    USING (true);

CREATE POLICY endpoints_visible ON webhooks.endpoints
    FOR SELECT TO axion_webhooks_jobs
    USING (true);
