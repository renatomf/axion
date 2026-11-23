-- The rails module's processor hands the inbox's credits to billing and
-- records what billing did (billing design, section 7.3). It may change only
-- where a credit is in that processing; what the rails sent, and the
-- evidence of what the core accepted, never change.
GRANT UPDATE (status, attempts, next_attempt_at, processed_at) ON rails.pix_credits TO finaxis_rails;
