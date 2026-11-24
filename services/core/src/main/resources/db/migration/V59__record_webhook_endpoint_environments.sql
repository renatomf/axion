-- The environment of each endpoint, its tenant's (events and webhooks design,
-- section 6.1). The jobs that work across tenants head the events they
-- publish for an endpoint with it (section 4.1), and the webhooks module
-- never calls the tenancy module to learn it.
ALTER TABLE webhooks.endpoints ADD COLUMN environment text NULL CHECK (environment IN ('TEST', 'LIVE'));

-- Every endpoint was registered by its tenant, whose row names the
-- environment.
UPDATE webhooks.endpoints AS endpoint
   SET environment = tenant.environment
  FROM tenancy.tenants AS tenant
 WHERE tenant.id = endpoint.tenant_id;

ALTER TABLE webhooks.endpoints ALTER COLUMN environment SET NOT NULL;
