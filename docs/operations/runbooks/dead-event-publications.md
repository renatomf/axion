# Dead event publications

**Alert:** `axion_events_publications_failed` is above zero.

A dead publication is a domain event that one listener failed to handle ten times, over about 13 hours ([events and webhooks design](../../design-docs/2026-11-23-events-and-webhooks.md), section 4.5). The listener's work for that event, such as an audit record or a webhook delivery, has not happened. Other listeners of the same event are not affected, and settlement never waits on a listener.

## 1. Find the dead publications

As a database role that may act as `axion_events`:

```sql
SET ROLE axion_events;
SELECT id, listener_id, event_type, publication_date
  FROM events.event_publication
 WHERE completion_date IS NULL AND status = 'FAILED' AND completion_attempts >= 10
 ORDER BY publication_date;
```

Locally: `just dead-publications`.

`axion_events` sees which listener and which type of event, never the event itself.

## 2. Find the cause

Search the core's logs for the listener's name around the publication's last attempts. A listener fails for a cause that outlives its retries: a bug in the consumer, a schema or permission it lacks, or a dependency down for longer than 13 hours. Fix the cause first; a resubmission before the fix only fails again.

## 3. Resubmit

```sql
SET ROLE axion_events;
SELECT events.resubmit_dead_publication('<id>', '<listener_id>');
```

Locally: `just resubmit-publication <id> <listener_id>`.

The function returns `true` when it started the publication's attempts over, and `false`, changing nothing, when the publication is not dead or belongs to another listener. Within a minute the resubmission job hands the event back to its listener; the consumer is idempotent, so an event it already handled has no second effect.

## 4. Confirm

The publication leaves the list of step 1, and `axion_events_publications_failed` returns to zero. If it fails again, it is retried on the schedule from its first attempt and the alert returns after its tenth.
