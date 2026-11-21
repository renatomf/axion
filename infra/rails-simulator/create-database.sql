-- Creates the rails simulator's own role and database on the project's
-- PostgreSQL server, kept apart from the core's database (rails simulator
-- design, section 8.3). The role owns its database and runs its own
-- migrations. Run as the server's owner, before the simulator starts: by the
-- rails-simulator-database service of infra/compose/compose.yaml on every
-- start, and from /docker-entrypoint-initdb.d by the simulator's tests. Every
-- statement is idempotent.
--
-- Environment variable: RAILS_SIMULATOR_DB_PASSWORD, the role's password.

\getenv password RAILS_SIMULATOR_DB_PASSWORD

SELECT format('CREATE ROLE rails_simulator LOGIN PASSWORD %L', :'password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rails_simulator')
\gexec

-- Keep the password in step with the environment if it was changed.
SELECT format('ALTER ROLE rails_simulator PASSWORD %L', :'password')
\gexec

SELECT 'CREATE DATABASE rails_simulator OWNER rails_simulator'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'rails_simulator')
\gexec

-- Only the simulator (and the server's owner) can connect to its database.
REVOKE CONNECT ON DATABASE rails_simulator FROM PUBLIC;
