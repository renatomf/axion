-- The workers that send webhooks disable an endpoint that kept failing, for
-- that reason only, and cancel its pending deliveries in the same
-- transaction (events and webhooks design, section 8.6). They read what the
-- event of the disabling carries: the endpoint without its secrets, its
-- tenant and its environment.
GRANT SELECT (tenant_id, environment, description, enabled_events, disabled_reason, created_at),
      UPDATE (status, disabled_reason, updated_at)
    ON webhooks.endpoints TO axion_webhooks_jobs;

-- Only an endpoint that receives changes, and it stays enabled or is
-- disabled for failing: the jobs never enable an endpoint, delete one or
-- disable one for the tenant.
ALTER POLICY receiving_endpoints_trackable ON webhooks.endpoints
    WITH CHECK (deleted_at IS NULL AND (status = 'ENABLED' OR disabled_reason = 'FAILING'));

-- A delivery still changes only while pending, and now also to canceled, as
-- a disabled endpoint's deliveries are; the jobs still never revive a
-- finished one.
ALTER POLICY pending_deliveries_sendable ON webhooks.deliveries
    WITH CHECK (status IN ('PENDING', 'SUCCEEDED', 'DEAD', 'CANCELED'));
