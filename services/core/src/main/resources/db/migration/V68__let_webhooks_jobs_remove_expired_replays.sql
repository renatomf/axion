-- A delivery's replays go with it, once its retention ends (events and
-- webhooks design, section 8.8). A replay asked for within the last 30 days
-- keeps its delivery, as an attempt does, and a pending one stays: the role
-- can remove only a replay that ended and was asked for 30 days ago or more,
-- measured with the database's time.
GRANT DELETE ON webhooks.replays TO axion_webhooks_jobs;

CREATE POLICY expired_replays_removable ON webhooks.replays
    FOR DELETE TO axion_webhooks_jobs
    USING (status <> 'PENDING' AND requested_at < now() - interval '30 days');
