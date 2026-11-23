-- The operator's command for a dead publication (events and webhooks design,
-- section 4.5): once the cause of its failures is fixed, an operator acting
-- as axion_events names the publication and its listener, and the
-- publication starts its attempts over. The resubmission job hands it back to
-- the listener within a minute. A publication that is not dead is left as it
-- is, so the command cannot hurry one still being retried.
--
-- Dead means failed after the tenth attempt, ResubmissionSchedule.ATTEMPTS in
-- the core.

-- The event's type is a class name, never tenant data: an operator listing
-- dead publications needs it to tell them apart.
GRANT SELECT (event_type) ON events.event_publication TO axion_events;
GRANT UPDATE (completion_attempts) ON events.event_publication TO axion_events;

CREATE FUNCTION events.resubmit_dead_publication(publication uuid, listener text)
    RETURNS boolean
    LANGUAGE sql
    SECURITY INVOKER
AS $$
    WITH revived AS (
        UPDATE events.event_publication
           SET completion_attempts = 1
         WHERE id = publication
           AND listener_id = listener
           AND completion_date IS NULL
           AND status = 'FAILED'
           AND completion_attempts >= 10
     RETURNING id
    )
    SELECT EXISTS (SELECT FROM revived);
$$;

REVOKE ALL ON FUNCTION events.resubmit_dead_publication(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION events.resubmit_dead_publication(uuid, text) TO axion_events;
