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
