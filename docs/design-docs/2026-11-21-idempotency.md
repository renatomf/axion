# Idempotency — Design

| | |
|---|---|
| **Status** | Draft |
| **Date** | 2026-11-21 |
| **Scope** | Idempotency of the public API's writes: the `Idempotency-Key` header, how a key is claimed and its response stored, conflicts, retention and the proof. Roadmap PRs 1.26–1.27. |
| **Parent** | [Platform overview](2026-11-17-platform-overview.md), sections 4.6, 7.1, 7.6 and 7.7 |

---

## 1. Problem

A tenant's system that creates a charge over the network cannot always tell whether its request worked. The connection may drop after the core committed the charge and before the response arrived; a timeout says nothing about what happened on the other side. The only safe reaction is to send the request again, and the second request must not create a second charge.

The ledger already makes each money movement idempotent on its source fact (ledger design, section 5.1), but a retried `POST /v1/charges` is not the same fact: without help it is a new charge, with its own BR Code, which a payer could pay as well. The API needs a way for a client to say "this is the request I sent before", and the core must answer with the first request's result and never run it twice, even when the repeats arrive together.

The platform overview fixes the shape: an `Idempotency-Key` header, a table of keys with the request's hash and the stored response, `409` for a request still in progress, `422` for a key reused with another payload, and 24 hours of retention (sections 7.6 and 7.7). This document decides how the core implements it.

## 2. Goals and non-goals

**Goals**

- Any number of repeats of a `/v1` write with one key, one after another or at the same time, have the effect of one request.
- A repeat gets the first request's response, byte for byte, and can tell that it is a replay.
- No crash, timeout or rollback leaves a key in a state that needs repair: a key either holds a complete result or does not exist.
- A key reused for a different request is refused, never answered with another request's result.
- A replay never gives a caller more than a fresh request would: it is authenticated, rate-limited and authorized like any request.

**Non-goals**

- Idempotency of `/internal` writes. The console's BFF sends them for a person's click; revoking a key is already harmless to repeat (tenancy design, section 7), and a key issued twice by a double click is visible and revocable. The same mechanism extends to them if the BFF needs it.
- Deduplication of rails callbacks and of event consumers. Their senders identify the fact, so they are deduplicated by `EndToEndId` (PR 1.38b) and per consumer (PR 1.44b).
- Idempotency beyond the retention period. A key is honored for at least 24 hours; a client that retries later sends a new request (section 6).

## 3. Contract

### 3.1 Which requests

Every `POST` under `/v1` requires an `Idempotency-Key` header. One rule for every write means an endpoint cannot be added without it and a client never needs to know which operations are protected. `GET` is safe and ignores the header; `DELETE` is idempotent by its semantics, since deleting twice leaves the same state, and needs no key. A `PUT` or `PATCH`, if one is ever added, is decided with it.

The header is described once in `v1.yaml`, as a reusable parameter, and a Spectral rule fails the lint when a `/v1` `POST` does not reference it, so the contract cannot drift from the server.

### 3.2 The key

- The client chooses the key, one per logical operation, and sends it unchanged on every retry. The contract recommends a random UUID.
- A key is 1 to 255 visible ASCII characters (`0x21`–`0x7E`). The core treats it as opaque text: it does not parse it, and two keys are equal only if their characters are. Clients that send the quoted form of the IETF draft `draft-ietf-httpapi-idempotency-key-header` are covered too, as long as they send it the same way each time.
- A key belongs to the tenant: two tenants may use the same key without effect on each other, and any API key of the tenant with the operation's scope gets the tenant's stored responses. Keys are per tenant rather than per API key so that a tenant who rolls its API key (tenancy design, section 4.3) while a request is being retried still gets one effect.
- A tenant is one environment, so test and live never share a key.

### 3.3 The request fingerprint

A key is bound to one request by its fingerprint, the SHA-256 of the request's path, a line feed, and its body:

```
<path>\n<body>
```

- The path is the one received, with its path parameters (`/v1/charges/ch_…/refunds`), so a key used to refund one charge cannot be reused to refund another. `/v1` writes take no query parameters.
- The body is the bytes received, not a parsed or canonical form: a client that retries sends the same bytes, and any difference, even in whitespace or key order, is a different request (section 9).
- The credential is not part of it (section 3.2).

The body must be read in full and hashed before the operation runs, so a `/v1` request body is limited to 64 KiB, and a larger one gets `413` before any key is looked up. No `/v1` write comes close: a charge is under 2 KiB.

### 3.4 Responses

| Case | Response | Code |
|---|---|---|
| First request with the key | The operation's response | — |
| Repeat of a request that completed | The stored response, with `Idempotent-Replayed: true` | — |
| Repeat while the first request is running | `409`, with `Retry-After: 1` | `request_in_progress` |
| The key with another fingerprint | `422` | `idempotency_key_reused` |
| No `Idempotency-Key` on a `/v1` `POST` | `400` | `idempotency_key_required` |
| A key that is empty, longer than 255 characters or outside visible ASCII | `400` | `invalid_idempotency_key` |
| A body over 64 KiB | `413` | `content_too_large` |

What a key stores:

- **Stored:** the response of a request whose operation ran and committed. That is its success (`2xx`) and the expected refusals that operations return as values, such as `422 limit_exceeded` (overview, section 19.1). A repeat gets the same refusal even if the request would now pass: the client asked once and was told no.
- **Not stored:** every response of a request that left no effect, because it was rejected before the operation ran (authentication, rate limit, scope, the checks above) or because its transaction rolled back (an invalid body, a `5xx`, a crash). A retry runs again, which is safe because nothing happened, and a transient failure never becomes the answer for 24 hours.

A replay carries the stored status, `Content-Type`, `Location` and body. The headers that describe the request being answered are its own: its correlation ID, its `RateLimit` headers and the security headers. `Idempotent-Replayed` follows Stripe's convention; the IETF draft defines no such header.

## 4. Claiming a key

### 4.1 One transaction

The design rests on one property of the platform: every `/v1` write is a local database transaction. Effects outside the core, such as webhooks, rails orders and the steps of a payout saga, leave through the outbox after commit (overview, section 8), never during the request. So a key's row can be written in the same transaction as the operation's effect, and the two commit or roll back together.

A request with a key runs in one transaction, at `READ COMMITTED`:

1. **Lock the key.** `pg_try_advisory_xact_lock(hashtext('idempotency.keys'), hashtext(<tenant>:<key>))`. If another transaction holds the lock, a request with the same key is running: answer `409 request_in_progress` at once, without waiting.
2. **Look the key up.** If the tenant has a row for the key, the request repeats one that committed. Matching fingerprints replay the stored response; different ones answer `422 idempotency_key_reused`. Either way the operation does not run.
3. **Run the operation.** The controller runs inside the transaction: the module's application service joins it, with `@Transactional`'s default propagation, and the response it writes is captured.
4. **Store the response.** Insert the key's row, with the fingerprint and the captured response.
5. **Commit.** The effect and the key's row become visible together. If anything fails in steps 3 to 5, both roll back and the key is free again.

This is the overview's insert-first in another form: the key is claimed, by its lock, before the operation runs. Its row is written last and complete, so there is no `IN_PROGRESS` state for a crash to leave behind.

### 4.2 Why it holds

- **One effect.** The primary key `(tenant_id, key)` admits one committed row per key, and an effect commits only with its key's row, so a second effect cannot commit. The advisory lock does not provide this guarantee; it turns a wait into a fast `409`. Two transactions with the same key that both reached step 4 would not both commit: the second's insert would wait for the first and then fail on the primary key, rolling its effect back.
- **No stuck keys.** A transaction's advisory lock ends with it: at commit, at rollback, or when PostgreSQL closes the session of a process that died. There is nothing to expire or recover.
- **A repeat after the commit replays.** At `READ COMMITTED` each statement sees every transaction committed before it starts, so the lookup of step 2 sees the first request's row.
- **Repeats hold no connections.** A repeat that finds the lock taken returns at once instead of waiting on the first request, so 50 parallel repeats cannot exhaust the connection pool for every tenant.
- **Collisions are harmless.** The lock's second half is a 32-bit hash, so two different keys may share a lock. Then one of them answers `409` while the other runs, and its client retries. A collision never replays one key's response for another: the lookup is by the key itself. The first half names the table, as the per-tenant lock of the API key limit does (`hashtext('access.api_keys')`), so the two kinds of lock never collide.

### 4.3 Where it runs

The step wraps the controller method at the point where the tenant is bound (tenancy design, section 5.3):

- **after** authentication, rate limiting and the permission check, so a stored response is given only to a caller who could make the request now: a revoked key gets `401` and a key without the scope `403`, never a replay;
- **inside** the tenant binding, so the transaction it opens is bound to the tenant and Row-Level Security applies to the key's row;
- **around** argument resolution and response writing, so the body is validated inside the transaction, and a rejected one rolls back unstored, and the stored bytes are the ones written.

A servlet filter runs before the permission check, and an aspect around the controller method runs after the body was validated and before the response is serialized; neither fits. The handler adapter that binds the tenant is the place. PR 1.26b makes it run the idempotency step without `access` and `idempotency` depending on each other.

### 4.4 The transaction boundary

The engineering standards declare transactions in the application layer (overview, section 19.2). This one is declared by the idempotency module's application service, in `IdempotencyApi.execute` (section 7), and the web step calls it, so the transaction spans the controller, as it must for the response to commit with the effect. Two consequences are deliberate:

- A `/v1` write cannot make a remote call during the request, since it would run inside the transaction. The standards already forbid remote calls inside a transaction; idempotency makes it structural.
- An operation that marks its transaction rollback-only, or returns a `5xx` as a value, rolls back the whole request, key included.

### 4.5 Refinements of the overview

| Overview | This design | Reason |
|---|---|---|
| A `state` column; the key is inserted before processing (section 7.7) | The key's lock is taken before processing; its row is written with the response, in the effect's transaction, and has no state | A key is complete or absent; nothing is left to recover after a crash |
| Repeats return stored errors (section 7.7) | Refusals that committed are stored; requests that rolled back, `5xx` included, are not | A request that rolled back had no effect, so its retry is safe |
| The idempotency check comes before validation (section 7.1, step 2) | It comes after authentication, rate limiting and the permission check, and the body is validated inside it | A replay must not bypass a scope |
| Error table (section 7.6) | Adds `400 idempotency_key_required`, `400 invalid_idempotency_key` and `413 content_too_large` | Every rejection has a stable code |
| No idempotency module (section 5.1) | An `idempotency` module, with its own schema and roles | One schema and one role per module (section 5.2) |

## 5. Schema

The `idempotency` schema follows the conventions of the other modules: `timestamptz`, `CHECK` constraints for every rule the database can express.

```
idempotency.keys
  tenant_id        uuid not null
  key              text not null          -- 1–255 visible ASCII characters (CHECK)
  fingerprint      bytea not null         -- SHA-256, 32 bytes (CHECK)
  response_status  smallint not null      -- 200–499 (CHECK): a 5xx is never stored
  content_type     text not null
  location         text null
  response_body    bytea not null         -- at most 64 KiB (CHECK)
  correlation_id   text not null          -- of the request that ran
  created_at       timestamptz not null
  expires_at       timestamptz not null   -- created_at + 24 hours (CHECK)
  primary key (tenant_id, key)
  index (expires_at)
```

- The primary key is the natural one: a row is only ever looked up by tenant and key, and a surrogate ID would be another index with no reader.
- Rows are never changed: the module role, `finaxis_idempotency`, may only `SELECT` and `INSERT`.
- The table has the tenant policy of the tenancy design (section 5.4), forced.
- The status check makes a bug that would store a server error fail its request instead of making the error permanent.
- The body is `bytea`, not `jsonb`: it is replayed as written, and `jsonb` would reorder its keys.

## 6. Retention

### 6.1 Expiry

A key is honored for at least 24 hours after its request ran, until `expires_at`. While its row exists a repeat replays; once the row is removed, the key is unknown and a request with it runs as new. The contract promises at least 24 hours, as Stripe does, rather than exactly 24: removal runs in batches, and the lookup does not depend on the clock.

### 6.2 Removal

A scheduled job deletes expired rows every hour, in batches of 1,000 until none is left, each batch in its own transaction so that it never holds locks for long. It spans tenants, so it runs as its own role, `finaxis_idempotency_jobs`, like the ledger's jobs (tenancy design, section 5.4), with policies that let it see and delete only expired rows:

```sql
CREATE POLICY expired_keys_visible ON idempotency.keys
    FOR SELECT TO finaxis_idempotency_jobs
    USING (expires_at <= now());

CREATE POLICY expired_keys_removable ON idempotency.keys
    FOR DELETE TO finaxis_idempotency_jobs
    USING (expires_at <= now());
```

The `SELECT` policy is needed because a `DELETE` with a condition reads the rows it deletes. Even with a bug in its query, the job can neither read a response still honored nor remove its key.

The rate limits bound how fast a tenant creates keys (tenancy design, section 6), and a stored response is one resource, typically under 2 KiB. If the table outgrows batch deletes, the path is daily partitions by `expires_at`, each dropped whole (section 9).

## 7. API

```java
public interface IdempotencyApi {
    IdempotentResult execute(IdempotentRequest request, Supplier<CapturedResponse> operation);
}

public record IdempotentRequest(TenantId tenant, IdempotencyKey key, Fingerprint fingerprint, String correlationId) {}

public sealed interface IdempotentResult permits Executed, Replayed, InProgress, KeyReused {}
public record Executed(CapturedResponse response) implements IdempotentResult {}   // the operation ran
public record Replayed(StoredResponse response) implements IdempotentResult {}     // a repeat of one that committed
public record InProgress() implements IdempotentResult {}                          // 409 request_in_progress
public record KeyReused() implements IdempotentResult {}                           // 422 idempotency_key_reused
```

- `execute` opens the request's transaction and refuses to join one already open: the key must commit with the operation's effect and nothing else. `Executed` means the operation ran; its response is stored if its status is below 500, and otherwise the transaction rolls back.
- A request in progress and a reused key are expected outcomes, so they are values, not exceptions (overview, section 19.1). An exception from the operation rolls the transaction back and propagates to the usual error handling.
- `IdempotencyKey` and `Fingerprint` exist only valid: a key of 1 to 255 visible ASCII characters, a fingerprint of 32 bytes.
- The module, `idempotency`, depends on `shared` only. Its persistence uses `JdbcClient`, as the other modules do: the lock, the lookup and the insert are three exact statements.
- The metric `finaxis_idempotency_requests_total{outcome}` counts `executed`, `replayed`, `in_progress` and `key_reused`. Logs carry the SHA-256 of the key, never the key: it is text the client chose and may hold anything.

## 8. Testing

| Suite | What it proves | PR |
|---|---|---|
| Domain unit | A key outside 1 to 255 visible ASCII characters is refused; the fingerprint changes with any byte of the path or the body | 1.26a |
| Schema (Testcontainers) | The constraints of section 5; the module role can neither update nor delete; with tenant A bound, B's keys are invisible, and one key in two tenants is two rows; the jobs role reads and deletes expired rows only | 1.26a |
| Store | A committed key replays; another fingerprint is `KeyReused`; a held lock is `InProgress`; an operation that throws leaves neither a row nor its effect; a `5xx` is not stored | 1.26a |
| Removal | Expired rows are removed in batches; a key still honored stays | 1.26a |
| Web | A missing or invalid key gets `400`, a body over 64 KiB `413`; a replay is byte for byte, with `Idempotent-Replayed`; a rejected body is not stored; a key without the scope gets `403` and a revoked key `401`, not the replay | 1.26b |
| Contract | The Spectral rule fails a `/v1` `POST` without the header | 1.26b |
| `financial-integrity` | 50 parallel repeats of one key yield one effect, and every answer is the stored response or `409`; a failure injected after the effect leaves nothing behind, and the retry has one effect | 1.27 |

Until `POST /v1/charges` (PR 1.35b), the web and proof suites call a `/v1` endpoint that exists only in the tests. Its effect is a ledger entry with a new source ID on every run, so the ledger's own idempotency cannot hide a duplicate: only the key can prevent the second entry.

## 9. Trade-offs and alternatives

| Alternative | Why it was rejected |
|---|---|
| **Commit an `IN_PROGRESS` row before the operation**, as in the overview and in Stripe's published design | The claim, the operation and the result need separate transactions. A crash after the operation commits and before the result does leaves the key `IN_PROGRESS` until a timeout lets a retry run the operation again: a second effect. Avoiding it needs recovery points in every operation. One transaction makes it impossible, at the price of no remote calls during `/v1` writes, which the outbox already avoids |
| **Wait for the first request** instead of answering `409` | Each repeat would hold a connection and a thread until the first request finishes; 50 parallel repeats would exhaust the pool for every tenant |
| **Keys in Redis** or another store | Not atomic with the effect: the moment between committing a charge and recording its key is exactly the failure this design removes |
| **An optional header**, as at Stripe | A retry without a key creates a second charge. A rule for every write cannot be forgotten by a client or by a new endpoint |
| **Store `5xx` responses**, as Stripe does | Turns a transient failure into the answer for 24 hours. Stripe stores them because its effects may have happened; here a `5xx` rolled back, so the retry is safe |
| **A canonical JSON fingerprint** (RFC 8785) | Tolerates re-serialization, but needs a canonicalizer, with its number and Unicode rules, on every write. Clients retry the same bytes, and a byte comparison is simple to explain |
| **Keys per API key** | A tenant rolling its key during retries would run the operation twice |
| **A servlet filter** | Runs before the permission check, so a key without the scope would get a stored response |
| **Daily partitions** from the start | Batch deletes are enough for phase 1 volumes; partitions remain the scale path |

## 10. Security considerations

The store answers on behalf of the operations, from a copy of their responses. The threats are a caller obtaining a response it should not see, an effect repeated, and repeats used to keep the core busy.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **Spoofing** | A caller obtains another tenant's stored response by guessing its key | Keys are per tenant; the tenant comes from the credential, the lookup filters by it and Row-Level Security hides other tenants' rows; a guessed key is a new request of the caller's own tenant |
| **Spoofing** | A captured request is replayed to repeat its effect | Within the retention period the replay gets the stored response and has no effect (overview, section 4.6); after it, the request is new, and money stays idempotent on its own facts (ledger source keys, `EndToEndId`) |
| **Tampering** | A client reuses a key to change an operation after the fact | The fingerprint covers the path and the body, and any difference is `422 idempotency_key_reused`; stored rows are never changed |
| **Repudiation** | A tenant disputes that a retried request ran once | The row keeps the correlation ID of the request that ran; replays are logged with it and counted |
| **Information disclosure** | Stored responses expose personal data or secrets | They are protected like the resources (Row-Level Security, module role), kept for the retention period only and never logged. A response that returns a secret shown once must not be stored in plaintext; the events and webhooks design (PR 1.43) settles it for webhook signing secrets, the first such `/v1` response |
| **Information disclosure** | Logs carry whatever a client put in its keys | Logs carry the key's hash, never the key |
| **Denial of service** | A flood of repeats or of new keys | A repeat answers `409` at once and holds no connection; new keys are bounded by the rate limits; bodies and stored responses are capped at 64 KiB; expired rows are removed every hour |
| **Elevation of privilege** | A replay gives a caller a response it could not obtain itself | The step runs after authentication, rate limiting and the permission check; the jobs role sees expired rows only; the module role can neither change nor delete a row |

## 11. Delivery

| PR | Delivers | Sections |
|---|---|---|
| 1.26a | The `idempotency` module: the keys table, its roles and policies; `IdempotencyApi.execute` with the lock, the lookup and the insert; the removal job | 4.1, 4.2, 4.4, 5, 6, 7 |
| 1.26b | The web step: header and body checks, the fingerprint, its place in the handler adapter, response capture and replay; the header in `v1.yaml` with its Spectral rule; the idempotency guide in `docs/api` | 3, 4.3 |
| 1.27 | The `financial-integrity` proof | 8 |

`POST /v1/charges` (PR 1.35b) is the first real `/v1` write, and it is idempotent with nothing of its own.

## 12. Decisions from review

1. **The header is required on every `/v1` `POST`.** A retry without a key would create a second charge, and one rule for every write cannot be forgotten by a client or by a new endpoint. A missing header gets `400 idempotency_key_required` (section 3.4).
2. **A `5xx` is never stored.** Its transaction rolled back, so nothing happened and the retry is safe; storing it would turn a transient failure into the answer for the whole retention period (section 3.4).
3. **The fingerprint hashes the raw body.** Clients retry the same bytes, and a byte comparison needs no canonicalizer; a client that re-serializes differently gets `422 idempotency_key_reused`, which the idempotency guide (PR 1.26b) explains.
