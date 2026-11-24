-- An endpoint that stops receiving, deleted or disabled, has its pending
-- replays canceled with its pending deliveries, in the same transaction
-- (events and webhooks design, sections 6.3, 8.6 and 8.7): enabled again, it
-- receives only what is asked for afresh. The module role does it for the
-- tenant's changes, the workers when they disable a failing endpoint; each
-- changes only what a cancellation sets.
GRANT SELECT (status), UPDATE (status, leased_until) ON webhooks.replays TO axion_webhooks;

CREATE POLICY pending_replays_cancelable ON webhooks.replays
    FOR UPDATE TO axion_webhooks
    USING (tenant_id = shared.current_tenant() AND status = 'PENDING')
    WITH CHECK (tenant_id = shared.current_tenant() AND status = 'CANCELED' AND leased_until IS NULL);

-- The workers act across tenants, as on the deliveries (V55).
GRANT SELECT (id, delivery_id, status), UPDATE (status, leased_until) ON webhooks.replays TO axion_webhooks_jobs;

CREATE POLICY replays_visible ON webhooks.replays
    FOR SELECT TO axion_webhooks_jobs
    USING (true);

CREATE POLICY pending_replays_sendable ON webhooks.replays
    FOR UPDATE TO axion_webhooks_jobs
    USING (status = 'PENDING')
    WITH CHECK (status = 'CANCELED' AND leased_until IS NULL);
