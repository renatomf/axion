-- Gives axion_app, the role the core service logs in as, its login and
-- password (tenancy design, section 5.6). Its privileges come from the core's
-- migrations; this script holds only the credential, which a migration never
-- does. Run before the migrations, as the server's owner: by the
-- core-database service of infra/compose/compose.yaml on every start, and
-- from /docker-entrypoint-initdb.d by the tests' PostgreSQL. Every statement
-- is idempotent.
--
-- Environment variable: AXION_APP_DB_PASSWORD, the role's password.

\getenv password AXION_APP_DB_PASSWORD

SELECT format('CREATE ROLE axion_app LOGIN NOINHERIT PASSWORD %L', :'password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'axion_app')
\gexec

-- Also gives the login to a role a migration created without one, and keeps
-- the password in step with the environment if it was changed.
SELECT format('ALTER ROLE axion_app LOGIN NOINHERIT PASSWORD %L', :'password')
\gexec
