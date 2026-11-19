-- Creates Keycloak's own role and database on the project's PostgreSQL server,
-- kept apart from the core's database (ADR 0005). Run by the keycloak-database
-- service in infra/compose/compose.yaml on every start; every statement is
-- idempotent, so it also works on a volume created before Keycloak existed.
--
-- psql variable: password, the keycloak role's password.

SELECT format('CREATE ROLE keycloak LOGIN PASSWORD %L', :'password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'keycloak')
\gexec

-- Keep the password in step with the environment if it was changed.
SELECT format('ALTER ROLE keycloak PASSWORD %L', :'password')
\gexec

SELECT 'CREATE DATABASE keycloak OWNER keycloak'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')
\gexec

-- Only Keycloak (and the server's owner) can connect to Keycloak's database.
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;
