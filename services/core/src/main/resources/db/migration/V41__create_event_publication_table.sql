-- The publication registry of the events module (events and webhooks design,
-- section 4.2): Spring Modulith 2.1's table, as its schema-postgresql.sql
-- defines it, so the database has one source of migrations. Modulith writes a
-- row per listener of each event in the publishing transaction, and marks it
-- completed once the listener returns.
--
-- The registry is the platform's, not a tenant's: it has no tenant column and
-- no policy. Modulith writes it with the connection's own role, so only the
-- login role is granted on it; the module roles cannot read it.
CREATE SCHEMA events;

CREATE TABLE events.event_publication (
    id                      uuid        NOT NULL,
    listener_id             text        NOT NULL,
    event_type              text        NOT NULL,
    serialized_event        text        NOT NULL,
    publication_date        timestamptz NOT NULL,
    completion_date         timestamptz,
    status                  text,
    completion_attempts     integer,
    last_resubmission_date  timestamptz,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON events.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx
    ON events.event_publication (completion_date);

GRANT USAGE ON SCHEMA events TO axion_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON events.event_publication TO axion_app;
