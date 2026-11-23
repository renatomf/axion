# Events and Webhooks — Design

| | |
|---|---|
| **Status** | Draft |
| **Date** | 2026-11-23 |
| **Scope** | The `events`, `webhooks`, `audit` and `notifications` modules of the core: domain events and their publication registry, the public event catalog, webhook endpoints and their SSRF defenses, signed delivery with retries and replay, the hash-chained audit trail and the per-tenant live stream. Roadmap PRs 1.44–1.49. |
| **Parent** | [Platform overview](2026-11-17-platform-overview.md), sections 3.2, 4.6, 4.7, 5.1, 5.2, 7.6 and 8 |

---

## 1. Problem

A tenant's system learns that a charge was paid by being told. Polling `GET /v1/charges/{id}` works, but it is slow, wasteful and easy to get wrong; what the tenant expects is a request to its own server, once the money has arrived, that it can trust came from Axion and that arrives even if its server was down when the payment settled. Inside the core the same fact matters to other modules: the audit trail records it, and the console shows it to whoever is looking at the dashboard.

None of these may slow down or break settlement (overview, section 5.2, rule 5). Settlement commits a charge, a payment and a ledger entry in one transaction; what follows it happens after the commit, possibly much later, and must never be lost: an event published by a transaction that committed is delivered, even if the core restarts the moment after. Each consumer receives it at least once, so each must be idempotent.

Webhooks add threats of their own. The tenant gives Axion a URL, and Axion makes requests to it: a URL that points inside Axion's network turns the core into a proxy for an attacker (server-side request forgery). The tenant must be able to tell Axion's requests from forged ones, and a tenant's endpoint that is slow or down must not delay anyone else's.

The overview fixes the shape: in-process domain events through Spring Modulith's persistent publication registry, a CloudEvents envelope, HMAC-SHA256 signatures with a timestamp, a PostgreSQL delivery queue with `SKIP LOCKED`, exponential backoff for about three days, endpoint disabling, a delivery log with manual replay, SSRF protection with DNS rebinding defended, a hash-chained audit trail and a per-tenant SSE stream (sections 3.2, 4.6, 4.7, 7.6 and 8). This document decides how the four modules implement them.

## 2. Goals and non-goals

**Goals**

- An event published in a transaction that commits is handled by every consumer at least once, whatever fails after the commit; an event of a transaction that rolls back is never handled.
- Every consumer has one effect per event, however often the event is delivered to it.
- A tenant registers HTTPS endpoints and receives every event it subscribed to, signed so that it can verify the sender, the content and the time.
- No webhook request reaches a loopback, private, link-local or metadata address, at registration or at connection.
- A failing endpoint is retried with backoff for about three days, then disabled; every attempt is recorded, and the tenant can replay any delivery.
- A slow or failing endpoint never delays deliveries to other endpoints, nor settlement.
- Every security-relevant action is recorded in an append-only audit trail whose tampering a check detects.
- A member watching the console sees a payment arrive within a second of its settlement.

**Non-goals**

- Kafka, and the extraction of `webhooks` into its own service. Both are phase 5 (overview, sections 5.4 and 8); this design keeps them possible without changing domain code.
- Mobile push and email. The `notifications` module delivers the SSE stream in phase 1; push arrives with the mobile app (phase 1b) and email later.
- Payout events. They arrive with payouts in phase 2 and follow this catalog's rules.
- A list of events on the public API (`GET /v1/events`). A tenant sees its events through the delivery log (section 8.7).
- Ordering guarantees between events (section 5.3).
- Asymmetric webhook signatures. HMAC is what the overview fixes and what receivers expect; Ed25519 signatures can be added beside it later (section 14).
- An audit viewer in the console. The trail is recorded and verified in phase 1; its screen comes with the back office.

## 3. Model

### 3.1 Concepts

| Concept | Meaning |
|---|---|
| **Domain event** | A Java record that a module publishes in the transaction of a change, such as `ChargePaid`. It belongs to its module's API and may change with the code |
| **Publication registry** | Spring Modulith's table of the events published and the listeners that still have to handle them. It is written in the publishing transaction, so an event exists exactly when its change does: it is the core's transactional outbox |
| **Consumer** | A listener in another module that reacts to a domain event, after the commit and in its own transaction |
| **Public event** | The form of a domain event that leaves the core: a CloudEvent with a type such as `charge.paid` and a versioned schema. It is a contract, like the API |
| **Webhook endpoint** | A tenant's HTTPS URL, the event types it wants, and the secret that signs the requests sent to it |
| **Delivery** | One public event to be sent to one endpoint. It is retried until it succeeds or its attempts run out |
| **Attempt** | One HTTP request of a delivery and what came back: the status, the time it took, or why it failed |
| **Audit record** | One security-relevant action, who did it and when, stored with the hash of the record before it |
| **Live stream** | The server-sent events a member's console receives for one tenant, while it is open |

### 3.2 Two kinds of events

A domain event is internal: modules publish and consume it in the same process, it carries Java types, and it changes whenever the code needs it to. A public event is the promise made to tenants' systems: its type, its fields and their meaning change only under the versioning rules of section 5.2. Keeping them apart lets a module refactor its events without breaking a tenant, and makes the public catalog one place to review.

The `events` module turns domain events into public ones (section 5.1). `webhooks` sends them to endpoints and `notifications` to live streams, so a tenant's server and its console read the same payload. `audit` consumes domain events directly: it records actions, not the public contract.

### 3.3 Event catalog

| Domain event | Module | Public type | Webhook | Live stream | Audit |
|---|---|---|---|---|---|
| `ChargeCreated` | `billing` | `charge.created` | ✓ | ✓ | |
| `ChargePaid` | `billing` | `charge.paid` | ✓ | ✓ | ✓ |
| `ChargeExpired` | `billing` | `charge.expired` | ✓ | ✓ | |
| `PaymentFlagged` | `billing` | `payment.flagged` | ✓ | ✓ | ✓ |
| `ChargeRefunded` | `billing` | `charge.refunded` | ✓ | ✓ | ✓ |
| `ApiKeyIssued`, `ApiKeyRolled`, `ApiKeyRevoked` | `access` | — | | | ✓ |
| `MemberRolesChanged` | `tenancy` | — | | | ✓ |
| `WebhookEndpointCreated`, `WebhookEndpointUpdated`, `WebhookEndpointDeleted`, `WebhookSecretRolled` | `webhooks` | — | | | ✓ |
| `WebhookEndpointDisabled` | `webhooks` | `webhook_endpoint.disabled` | | ✓ | ✓ |

- `charge.paid` is sent once per charge, by the payment that settles it; a payment that does not settle it is `payment.flagged`, with its reason. A charge settled after it expired is `charge.paid` too (billing design, section 3.2).
- `charge.refunded` is sent for every refund, of the settling payment or of a flagged one, with the refund and the charge after it.
- Key, member and endpoint events are security events. They are audited, not sent to the tenant's integrations: a leaked key must not learn of the keys that replace it.
- `webhook_endpoint.disabled` reaches the console's live stream, so the tenant sees why its deliveries stopped (section 8.6); sending it to the endpoint that failed would be pointless.
- `ChargeCreated` is not audited: creating a charge moves no money and is already recorded with its idempotency key. `EntryPosted` (overview, section 6.7) is not published until a consumer needs it, with `reporting` in phase 3.

### 3.4 Refinements of the overview

| Overview | This design | Reason |
|---|---|---|
| `webhooks`, `audit` and `notifications` depend on events only (section 5.1) | A new `events` module owns the registry's table, the public catalog and the CloudEvents envelope; `webhooks` and `notifications` call it to render public events | One renderer for webhooks and the live stream; a pure function, so the event-only modules still never call a module that can slow them down |
| "Versioned JSON Schemas in the repository" (section 8) | The schemas are the `webhooks` section of `v1.yaml`, OpenAPI 3.1's description of outgoing requests | OpenAPI 3.1 schemas are JSON Schema 2020-12; the contract tests and Spectral already read `v1.yaml` |
| "HMAC-SHA256 signature with timestamp" (section 4.6) | The [Standard Webhooks](https://www.standardwebhooks.com) headers and signature scheme | An open specification with verification libraries in many languages; it signs the ID and timestamp with the body and carries several signatures during a secret's rotation |
| `ApiKeyIssued`, `ApiKeyRevoked` and `MemberRolesChanged` (tenancy design, section 7) | `ApiKeyRolled` is published too | A roll issues a key and schedules the end of another; one event records both |
| Dead letters for consumers "with reprocessing from the back office" (section 7.6) | Failed publications stay in the registry, retried with backoff; after the last attempt they are counted, alerted and resubmitted by an operator command | The back office does not exist in phase 1; the registry already holds what a dead-letter table would copy |
| "The endpoint is disabled and the tenant notified" (section 7.6) | The console is notified through the live stream and a banner; email arrives with the email channel | Phase 1 has no email channel |

## 4. Domain events

### 4.1 Shape

Every domain event implements `DomainEvent`, in `shared`:

```java
public interface DomainEvent {
    EventId id();               // UUIDv7, generated when the event is created
    TenantId tenant();          // the tenant whose data changed
    Environment environment();
    Actor actor();              // a member, an API key, or a job of the platform
    String correlationId();     // the request's, or the job run's
    Instant occurredAt();       // from the injected Clock, in the publishing transaction
}
```

- An event carries what its consumers need to act without calling back: `ChargePaid` carries the `ChargeDetail` as it is after settlement, `ChargeRefunded` the `RefundView` and the `ChargeDetail`. Event-only modules are never called synchronously (overview, section 5.2, rule 5), so they cannot read the charge themselves.
- An event carries no secret and no personal data. A charge's `metadata` is the tenant's own data, sent only to the tenant's own endpoints, and is never logged.
- `Actor` is a sealed type: `Member(MemberId)`, `ApiKey(ApiKeyId)` or `System(String job)`, such as `System("charge-expiry")`. The audit trail records it; public events leave it out.
- The event ID is the public event's ID (section 5.1) and the key every consumer deduplicates by (section 4.4).

### 4.2 Publication registry

The core uses Spring Modulith's JDBC registry (`spring-modulith-starter-jdbc`). When a transaction publishes an event, Modulith writes one row per listener that will handle it into `events.event_publication`, in the same transaction; after the commit it hands the event to each listener, and marks its row completed when the listener returns. A publication whose listener failed, or whose process stopped first, stays incomplete and is handed to the listener again (section 4.5).

- The table is the one Modulith 2.1 defines, created by `events`' Flyway migration in the `events` schema; Modulith's own schema initialization is off, so the database has one source of migrations.
- The registry is the platform's, not a tenant's: it has no tenant column and no policy. Modulith writes it with the connection's login role, `axion_app`, which alone is granted on it; a module publishes after its role switch returns (tenancy design, section 5.6), still inside its transaction. The module roles cannot read it.
- `serialized_event` is the event as JSON, written by Modulith's Jackson serializer. Events are records of IDs, values and views, so the serializer needs no customization; a test serializes and reads back every event of the catalog.
- Completed publications are deleted after 7 days by a job of `events`; incomplete ones are never deleted automatically.

### 4.3 Listeners

A consumer is a method annotated `@ApplicationModuleListener`: it runs after the publishing transaction commits, on another thread, in a new transaction of its own. Before it touches the database, it binds the event's tenant with `TenantContext.run(event.tenant(), event.environment(), …)` (tenancy design, section 5.3), so Row-Level Security applies to the consumer as it does to a request.

- Listeners run on a bounded executor of virtual threads, 32 at a time; a burst of events waits in the executor's queue, never in a request thread.
- A listener does one thing in one transaction: record an audit record, create deliveries, push to streams. A listener that has to call something slow, such as a tenant's endpoint, records the work and leaves it to a job (section 8).
- A listener never publishes into the transaction of the module whose event it handles, and no listener sits on the money path: settlement commits whether or not any listener exists.

### 4.4 Idempotent consumers

Delivery is at least once: a listener may be handed the same event again after a crash between its commit and Modulith's completion mark, or after an operator resubmits it. Every consumer therefore records the events it handled, in its own schema, in the transaction of its effect:

```
<schema>.consumed_events
  consumer      text not null
  event_id      uuid not null
  consumed_at   timestamptz not null
  primary key (consumer, event_id)
```

`IdempotentConsumer`, in `shared`, inserts the row with `ON CONFLICT DO NOTHING` and runs the effect only when the row was inserted; a duplicate commits nothing else and returns. The effect and the row commit together, so an event is handled once even if the duplicate arrives while the first is still running: the second insert waits for the first transaction's key and then finds it.

Where a consumer's own table already has the event ID as a unique key, as `webhooks.events` does (section 8.1), that key is its record and no `consumed_events` row is needed. Rows older than 30 days are deleted; a registry that resubmits a 30-day-old event has a problem an operator must see first (section 4.5).

### 4.5 Failed publications

A listener that throws leaves its publication incomplete. A job of `events` resubmits incomplete publications every minute, with a backoff by attempt: 1, 5 and 30 minutes, then every 2 hours, up to 10 attempts. Modulith also resubmits them when the core starts, so a publication interrupted by a restart is handled within seconds of the next start.

After the tenth attempt a publication is left alone: it is a dead letter. It is counted (`axion_events_publications_failed`), alerted on, and kept until an operator resubmits it, with a command that names the listener and the publication, once the cause is fixed. A consumer that keeps failing never blocks the others: each listener has its own publication of the event.

## 5. Public events

### 5.1 Envelope

A public event is a [CloudEvent 1.0](https://github.com/cloudevents/spec) in structured JSON mode:

```json
{
  "specversion": "1.0",
  "id": "evt_01jdr3b6f7g8h9j0k1m2n3p4q5",
  "source": "urn:axion:test",
  "type": "charge.paid",
  "subject": "ch_01jdq8k3m4n5p6q7r8s9t0v1w2",
  "time": "2026-11-23T14:03:11.482Z",
  "datacontenttype": "application/json",
  "dataschema": "https://github.com/renatomf/axion/blob/main/services/core/openapi/v1.yaml#charge.paid.v1",
  "data": {
    "object": { "id": "ch_01jdq8k3m4n5p6q7r8s9t0v1w2", "object": "charge", "status": "paid", "…": "…" }
  }
}
```

| Attribute | Value |
|---|---|
| `id` | The domain event's ID as a public ID with the prefix `evt_`. The same for every delivery and replay of the event, so receivers deduplicate by it |
| `source` | `urn:axion:test` or `urn:axion:live`: the environment, so a receiver can tell a test event from a live one without a lookup |
| `type` | The public type of section 3.3 |
| `subject` | The public ID of the resource the event is about |
| `time` | The domain event's `occurredAt`, in UTC with milliseconds |
| `dataschema` | The schema of `data` and its version (section 5.2) |
| `data.object` | The resource as `GET` returns it at the event's time: the charge with its payments and refunds; for `payment.flagged`, the payment with its `charge`; for `charge.refunded`, the charge, with the refund named by `data.refund` |

- `data` is rendered once, when `webhooks` records the event (section 8.1), and stored as bytes: every attempt and every replay sends exactly the bytes first sent, so a signature verified once stays valid evidence.
- The resource is a snapshot, not a pointer: a receiver can act without calling the API. A receiver that needs the current state, because a later event may have changed it, reads the resource by `subject`.
- `EventCatalog.render(DomainEvent)`, in `events`, is the only place a public event is built. It is a pure function of the event and the clock, with no database access, so `webhooks` and `notifications` can call it without depending on anything slow.

### 5.2 Versioning

Each public type has a schema in the `webhooks` section of `v1.yaml`, one entry per type and version (`charge.paid.v1`), referencing the same component schemas as the API: a charge in a webhook is the charge of `GET /v1/charges/{id}`.

- **Additive changes** keep the version: a new optional field, a new value of a field the reference documents as open, a new event type. Receivers must ignore fields they do not know; the reference says so.
- **Breaking changes** add a version: a removed or renamed field, a changed meaning, a new required field. During a deprecation period of at least six months both versions are sent, as types `charge.paid` and `charge.paid.v2`, and an endpoint chooses which it subscribes to; afterwards the old one stops.
- The contract tests render every type and validate it against its schema, strictly, as they do for responses (section 13).

### 5.3 Ordering and duplicates

Events are delivered at least once and in no guaranteed order. A retried `charge.created` can arrive after the `charge.paid` of the same charge; a delivery whose response was lost is sent again. The reference tells receivers to deduplicate by `id`, to compare `time` or the resource's state before overwriting newer data, and to answer quickly and process later. Ordering per resource would hold every later delivery of a charge behind a failing one; no Pix integration needs it, because the snapshot carries the state.
