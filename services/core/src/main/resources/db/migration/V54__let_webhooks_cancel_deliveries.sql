-- An endpoint that stops receiving, deleted or disabled, has its pending
-- deliveries canceled in the same transaction (events and webhooks design,
-- sections 6.3 and 8.1). The role finds them by endpoint and status, and
-- changes only what a cancellation sets.
GRANT SELECT (id, tenant_id, endpoint_id, status) ON webhooks.deliveries TO axion_webhooks;
GRANT UPDATE (status, next_attempt_at, leased_until) ON webhooks.deliveries TO axion_webhooks;
