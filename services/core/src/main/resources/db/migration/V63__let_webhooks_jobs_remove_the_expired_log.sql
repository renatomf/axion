-- Events, deliveries and attempts are kept for 30 days, then removed by a
-- job of the webhooks module, as axion_webhooks_jobs (events and webhooks
-- design, section 8.8). A deleted endpoint keeps its row until its log is
-- gone, then goes with its secrets.
--
-- Each policy compares with the database's own time, so the role can never
-- remove a row younger than 30 days, whatever the job's clock or SQL: the log
-- stays evidence for its whole retention.

-- A delivery goes once it ended and its last attempt is 30 days old, with
-- its attempts; the job reads only what decides it, never a response.
GRANT SELECT (delivery_id, started_at), DELETE ON webhooks.attempts TO axion_webhooks_jobs;
GRANT SELECT (created_at), DELETE ON webhooks.deliveries TO axion_webhooks_jobs;

CREATE POLICY attempts_visible ON webhooks.attempts
    FOR SELECT TO axion_webhooks_jobs
    USING (true);

CREATE POLICY expired_attempts_removable ON webhooks.attempts
    FOR DELETE TO axion_webhooks_jobs
    USING (started_at < now() - interval '30 days');

CREATE POLICY expired_deliveries_removable ON webhooks.deliveries
    FOR DELETE TO axion_webhooks_jobs
    USING (status <> 'PENDING' AND created_at < now() - interval '30 days');

-- An event goes once it is 30 days old and no delivery sends it.
GRANT SELECT (occurred_at), DELETE ON webhooks.events TO axion_webhooks_jobs;

CREATE POLICY expired_events_removable ON webhooks.events
    FOR DELETE TO axion_webhooks_jobs
    USING (occurred_at < now() - interval '30 days');

-- A deleted endpoint goes 30 days after its deletion, once no delivery is
-- left, with every secret it still had.
GRANT DELETE ON webhooks.endpoints TO axion_webhooks_jobs;

CREATE POLICY expired_endpoints_removable ON webhooks.endpoints
    FOR DELETE TO axion_webhooks_jobs
    USING (deleted_at < now() - interval '30 days');

CREATE POLICY expired_endpoints_secrets_removable ON webhooks.secrets
    FOR DELETE TO axion_webhooks_jobs
    USING (EXISTS (SELECT 1 FROM webhooks.endpoints AS endpoint
                    WHERE endpoint.id = secrets.endpoint_id
                      AND endpoint.deleted_at < now() - interval '30 days'));

-- The job finds the ended deliveries, the events and the deleted endpoints
-- that are old enough.
CREATE INDEX deliveries_ended_created_at_idx ON webhooks.deliveries (created_at) WHERE status <> 'PENDING';
CREATE INDEX events_occurred_at_idx ON webhooks.events (occurred_at);
CREATE INDEX endpoints_deleted_at_idx ON webhooks.endpoints (deleted_at) WHERE deleted_at IS NOT NULL;
-- Whether an event still has a delivery; an endpoint's are found by
-- deliveries_endpoint_id_idx (V62).
CREATE INDEX deliveries_event_id_idx ON webhooks.deliveries (event_id);
