-- Whether an attempt was a replay the tenant asked for, not one of its
-- delivery's schedule (events and webhooks design, section 8.7). The
-- attempts recorded so far were all scheduled.
ALTER TABLE webhooks.attempts ADD COLUMN replay boolean NOT NULL DEFAULT false;
