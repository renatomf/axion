-- The role of the events module's own work on the publication registry
-- (events and webhooks design, section 11): counting incomplete and dead
-- publications for the metrics. Modulith keeps writing the registry as the
-- login role (V41); this role reads only the columns that describe a
-- publication's progress, never serialized_event, which holds tenants' data.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_events') THEN
        CREATE ROLE axion_events NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA events TO axion_events;
GRANT SELECT (id, listener_id, publication_date, completion_date, status, completion_attempts)
    ON events.event_publication TO axion_events;

-- The service switches to it with SET ROLE, as to a module role (V15).
GRANT axion_events TO axion_app WITH INHERIT FALSE, SET TRUE;
