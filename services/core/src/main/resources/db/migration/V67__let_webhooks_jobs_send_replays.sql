-- The workers send the replays a tenant asked for, as they send deliveries
-- (events and webhooks design, sections 8.2 and 8.7): they claim a pending
-- replay with a lease, sign and send its delivery's event, record the
-- request as a replay under its tenant and mark the replay sent. They change
-- nothing of the delivery itself, and never revive a replay that ended.
GRANT SELECT (tenant_id, requested_at, leased_until) ON webhooks.replays TO axion_webhooks_jobs;

ALTER POLICY pending_replays_sendable ON webhooks.replays
    WITH CHECK (status = 'PENDING' OR (status IN ('SENT', 'CANCELED') AND leased_until IS NULL));

-- The workers find the replays asked for and not yet sent by this index.
CREATE INDEX replays_pending_idx ON webhooks.replays (requested_at) WHERE status = 'PENDING';
