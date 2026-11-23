-- The expiry job expires the due charges of one tenant per transaction, so
-- that each charge's ChargeExpired is published in the transaction that
-- expired it, with its tenant bound (billing design, section 9). It finds the
-- tenants with due charges by their tenant_id; its policies are unchanged, so
-- it still changes active charges only, and only to EXPIRED.
GRANT SELECT (tenant_id) ON billing.charges TO axion_billing_jobs;
