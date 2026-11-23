-- Endpoints change and are deleted (events and webhooks design, sections
-- 6.3 and 11). A deleted endpoint keeps its row, marked by deleted_at, so
-- its delivery log stays readable until its retention ends; it receives
-- nothing and is never listed.
ALTER TABLE webhooks.endpoints ADD COLUMN deleted_at timestamptz NULL;

-- Only the columns a change or a deletion sets: an endpoint never moves to
-- another tenant, and its ID and creation time never change. The role still
-- cannot delete a row.
GRANT UPDATE (url, description, enabled_events, status, disabled_reason, updated_at, deleted_at)
    ON webhooks.endpoints TO axion_webhooks;
