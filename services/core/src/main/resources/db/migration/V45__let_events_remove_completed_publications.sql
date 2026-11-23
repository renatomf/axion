-- Completed publications are removed 7 days after their completion by a job
-- of the events module (events and webhooks design, section 4.2), so the
-- registry holds what is still owed and a week of what was done. The job
-- deletes by completion_date, which is set only once a listener has handled
-- its event: an incomplete publication is never removed.
GRANT DELETE ON events.event_publication TO axion_events;
