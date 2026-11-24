# Broken audit chain

**Alert:** `axion_audit_chain_verified` is 0 for a chain.

Each tenant's audit records form a hash chain: every record stores the hash of the one before it ([events and webhooks design](../../design-docs/2026-11-23-events-and-webhooks.md), section 9). Each night at 03:00 (America/Sao_Paulo) a job verifies every chain and sets the metric to 1 when the chain is intact and 0 when it is not. No role of the service can change or remove a record, so a broken chain means that someone with the database owner's privileges, or a restore, changed the trail. Treat it as a security incident: the trail is the evidence.

Do not repair the chain. Recomputing hashes would make the change invisible and destroy what an investigation needs.

## 1. Find the break

The job logs one line per broken chain, at `ERROR`, with the chain's tenant in `tenant_id`:

```
Audit chain ten_01jdr3b6f7g8h9j0k1m2n3p4q5 is broken at sequence 42 (ALTERED); head at sequence 57 with hash 5f7c…
```

| Kind | What the stored chain shows |
|---|---|
| `ALTERED` | The record at the sequence no longer has the hash of its own fields: it was changed |
| `UNLINKED` | The record does not link to the hash of the record before it: that record was changed and its hash recomputed, or replaced |
| `MISSING` | No record holds the sequence: a record was removed, or records were moved |
| `HEAD_MISMATCH` | The chain's head is not its last record: records were removed from the end, added past it, or the head itself was changed or removed (sequence 0) |

The job reports the first break only: what follows it cannot be trusted to describe the chain.

## 2. Look at the records around it

As a database role that may act as `axion_audit_checks`, which reads every chain and changes nothing:

```sql
SET ROLE axion_audit_checks;
SELECT sequence, occurred_at, actor_type, actor_id, action, resource_type, resource_id, correlation_id,
       event_id, details, encode(previous_hash, 'hex') AS previous_hash, encode(hash, 'hex') AS hash
  FROM audit.records
 WHERE tenant_id = '<tenant_id>' AND sequence BETWEEN <sequence> - 2 AND <sequence> + 2
 ORDER BY sequence;
SELECT sequence, encode(hash, 'hex') AS hash FROM audit.chain_heads WHERE tenant_id = '<tenant_id>';
```

## 3. Compare with the heads logged before

Every night the job logs each intact chain's head, outside the database:

```
Audit chain ten_01jdr3b6f7g8h9j0k1m2n3p4q5 is intact; head at sequence 57 with hash 5f7c…
```

Search the logs for the chain's lines of the nights before. A head logged earlier at a higher sequence than the current one means records were removed from the end of the chain together with its head: the one change the chain alone cannot show, so check it for every chain, not only the broken ones. The last night the chain was intact bounds when the change happened.

## 4. Preserve and escalate

1. Copy the chain's records and head, as they are, somewhere outside the database's reach, before anything else touches them.
2. Find who held the owner's privileges, and every restore or manual statement in the window of step 3, in the database's and the platform's access logs.
3. Escalate as a security incident. If a backup taken before the change holds the original records, the investigation may restore them; the chain verifies again once every record is the one appended.

## 5. Confirm

The metric is set again by the next night's run. It returns to 1 only when the chain verifies: a chain left as the incident found it stays at 0, and the alert with it, until the incident is closed.
