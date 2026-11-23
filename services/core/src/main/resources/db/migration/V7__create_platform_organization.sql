-- The platform's own organization and its two tenants (tenancy design,
-- sections 3.1 and 3.4). They own the platform's ledger accounts, such as
-- platform:settlement, one set per environment, so test money never reaches
-- live accounts. They have no members and no API keys.
--
-- The IDs are fixed, so code and other migrations can name them. They are
-- valid UUIDv7 values (version 7, variant 10) with a zero timestamp, which no
-- generated ID will ever have.
INSERT INTO tenancy.organizations (id, name, is_platform)
VALUES ('00000000-0000-7000-8000-000000000001', 'Axion platform', true);

INSERT INTO tenancy.tenants (id, organization_id, environment, status)
VALUES ('00000000-0000-7000-8000-000000000002', '00000000-0000-7000-8000-000000000001', 'TEST', 'ACTIVE'),
       ('00000000-0000-7000-8000-000000000003', '00000000-0000-7000-8000-000000000001', 'LIVE', 'ACTIVE');
