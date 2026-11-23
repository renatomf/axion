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

## 6. Webhook endpoints

### 6.1 Resource

```json
{
  "id": "we_01jdr2m4n5p6q7r8s9t0v1w2x3",
  "object": "webhook_endpoint",
  "url": "https://example.com/axion/webhooks",
  "description": "Order service",
  "enabled_events": ["charge.paid", "charge.refunded"],
  "status": "enabled",
  "disabled_reason": null,
  "secret": "whsec_…",
  "created_at": "2026-11-23T13:58:02Z"
}
```

- `url`: HTTPS only, at most 2,048 characters, validated as section 7.1 describes.
- `enabled_events`: one to 20 public types of the catalog, or `["*"]` for every type, including those added later. An unknown type is refused, so a typo does not silently subscribe to nothing.
- `status`: `enabled` or `disabled`; `disabled_reason` is `failing` (section 8.6) or `by_tenant`.
- `secret` appears only in the responses that create it: creating the endpoint and rolling its secret (section 6.2).
- At most 16 endpoints per tenant. Endpoints belong to a tenant, so to one environment: a test key manages test endpoints, which receive test events only (tenancy design, section 3.2).

### 6.2 Signing secrets

A secret is `whsec_` followed by 32 random bytes in base64, the format of Standard Webhooks. HMAC needs the secret itself to sign, so it cannot be hashed as API keys are: it is encrypted at rest with [Tink](https://developers.google.com/tink) AEAD (AES-256-GCM), with the endpoint's ID as associated data, so a ciphertext copied to another endpoint fails to decrypt. The key-encryption key comes from `AXION_WEBHOOK_SECRETS_KEY` and never reaches the database (overview, section 4.7).

- A secret is shown once and never logged; `.gitleaks.toml` gains a rule for its format.
- **Rolling** issues a new secret and keeps the old one signing for a grace period, from zero to 7 days (default 24 hours), so the tenant can deploy the new secret first. During the grace period each request carries two signatures (section 8.4), and the old secret is deleted when it ends. A secret is rolled again only after the previous grace period ends, so an endpoint never has more than two.

### 6.3 Operations

| Operation | Rule |
|---|---|
| Create | URL validated (section 7.1); secret generated and returned once; at most 16 endpoints |
| Read and list | Never the secret |
| Update | `url`, `description`, `enabled_events` and `status`; a new URL is validated again. Setting `status` to `enabled` clears `disabled_reason` and starts the endpoint afresh: deliveries that died while it was disabled are not resent; the tenant replays the ones it wants (section 8.7) |
| Delete | The endpoint stops receiving at once; its pending deliveries are canceled; its delivery log stays readable until its retention ends (section 8.8) |
| Roll secret | Section 6.2 |

Each operation publishes its event of section 3.3 for the audit trail. On `/internal`, every change needs step-up, as the overview requires for changing webhook URLs (section 4.3).

## 7. SSRF defenses

A webhook is a request the core makes to an address someone else chose. Checking the URL when it is registered is not enough: the name can resolve to a public address at registration and to `169.254.169.254` a minute later (DNS rebinding). The core therefore checks twice, with one rule, `PublicAddressPolicy`, in `webhooks`.

### 7.1 At registration

A URL is accepted only if:

- its scheme is `https`, it has a host, and it has no user information and no fragment;
- its host is a DNS name, not an IP literal, and not `localhost` or a name under `.localhost`, `.local`, `.internal` or `.home.arpa`;
- its port is 443 or between 1024 and 65535;
- its host resolves, and every address it resolves to is public.

An address is public unless it is in one of these ranges, for IPv4 and IPv6 alike, including IPv4 addresses written in IPv6 forms (mapped `::ffff:0:0/96`, translated `64:ff9b::/96`, 6to4 `2002::/16`):

| Range | Why |
|---|---|
| `0.0.0.0/8`, `::/128` | Unspecified: "this host" on many stacks |
| `127.0.0.0/8`, `::1/128` | Loopback |
| `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `fc00::/7` | Private networks |
| `100.64.0.0/10` | Carrier-grade NAT, used inside cloud networks |
| `169.254.0.0/16`, `fe80::/10` | Link-local, including the cloud metadata services at `169.254.169.254` and `fd00:ec2::254` (the latter also in `fc00::/7`) |
| `192.0.0.0/24`, `192.0.2.0/24`, `198.18.0.0/15`, `198.51.100.0/24`, `203.0.113.0/24`, `2001:db8::/32` | Protocol assignments, benchmarking and documentation |
| `224.0.0.0/4`, `240.0.0.0/4`, `ff00::/8` | Multicast, reserved and broadcast |

A refused URL is `422 validation_failed` on `url`, with a reason that names the rule (`must_be_https`, `must_be_public`) but never the addresses the name resolved to: the core does not answer DNS questions about its own network.

### 7.2 At connection

Deliveries use Spring's `RestClient` on Apache HttpClient 5, whose `DnsResolver` the core replaces: it resolves the host, drops every address the policy refuses, and fails the attempt when none is left. The connection is made to an address that was checked, so no second lookup can change it; TLS still verifies the certificate against the host name.

- Redirects are never followed: a `3xx` is a failed attempt. A redirect is the simplest way around the check.
- No proxy, no cookies, no authentication cache; every attempt is a new, plain request.
- Connection timeout 5 seconds, whole-response timeout 10 seconds, at most 64 KiB of response body read, of which the first 1 KiB is kept for the delivery log.
- In the `local` profile, `axion.webhooks.allowed-local-ports` lets a developer receive webhooks on `localhost` for chosen ports; the setting does not exist in the default profile, so no deployed core can enable it.

## 8. Delivery

### 8.1 From event to deliveries

`webhooks` listens to the domain events with a public type. For each, in one transaction:

1. It inserts the public event into `webhooks.events`, rendered by `EventCatalog.render`, keyed by the event's ID; a duplicate insert finds the key and stops (section 4.4).
2. It inserts one delivery per endpoint of the tenant that is enabled and subscribed to the type, with `next_attempt_at` now.

Endpoints created later do not receive earlier events, and a disabled endpoint receives none.

### 8.2 The queue

Workers drain `webhooks.deliveries` every second, as `axion_webhooks_jobs`. A worker claims a batch of up to 20 due deliveries in a short transaction and lets it commit before sending anything:

```sql
UPDATE webhooks.deliveries
   SET leased_until = now() + interval '30 seconds'
 WHERE id IN (SELECT id FROM webhooks.deliveries
               WHERE status = 'PENDING' AND next_attempt_at <= now()
                 AND (leased_until IS NULL OR leased_until < now())
               ORDER BY next_attempt_at
               LIMIT 20
               FOR UPDATE SKIP LOCKED)
RETURNING …
```

- `SKIP LOCKED` lets several workers, or several instances of the core, claim batches at once without waiting on each other or claiming the same row.
- The lease, not a held lock, keeps the claim during the HTTP request: no transaction and no connection stays open while a tenant's server takes ten seconds to answer. A worker that dies mid-request leaves its lease to expire, and the delivery is claimed again: at least once, which receivers already handle by `id` (section 5.3).
- Each attempt's result is written in a second short transaction that updates the delivery and inserts the attempt.
- No endpoint has more than 4 deliveries in flight; the claim skips deliveries of endpoints at their limit, so one slow endpoint holds at most 4 of the workers' requests and every other endpoint keeps its pace.
- The job role reads across tenants, like the billing jobs: its policies let it see deliveries, the events they send and their endpoints, and change only a delivery's status, lease, attempt count and next attempt.

### 8.3 The request

```
POST /axion/webhooks HTTP/1.1
Host: example.com
Content-Type: application/cloudevents+json; charset=utf-8
User-Agent: Axion-Webhooks/1.0
webhook-id: evt_01jdr3b6f7g8h9j0k1m2n3p4q5
webhook-timestamp: 1795442591
webhook-signature: v1,K5oZfzN95Z9UVu1EsfQmfVNQhnkZ2pj9o9NDN/H/pI4=

{"specversion":"1.0","id":"evt_01jdr3b6f7g8h9j0k1m2n3p4q5",…}
```

A `2xx` received within the timeout is a success; the body of the response is ignored. Anything else is a failure: another status, a timeout, a refused address, a TLS error, a redirect.

### 8.4 Signing

The signature follows Standard Webhooks: HMAC-SHA256, keyed with the secret's 32 bytes, over `{webhook-id}.{webhook-timestamp}.{body}`, base64-encoded and prefixed with the scheme version `v1,`.

- `webhook-timestamp` is the attempt's time in Unix seconds, so a replay of an old attempt is signed afresh. Receivers reject a timestamp more than five minutes from their clock, which bounds the replay of a captured request; the signed `webhook-id` lets them reject one inside that window.
- During a secret's grace period, `webhook-signature` carries two signatures separated by a space, new first; a receiver accepts the request if any one verifies.
- The body is signed as the bytes sent, never re-serialized, so a receiver verifies the raw body before parsing it. The webhook guide (`docs/api/webhooks.md`) shows how, with the reference libraries.

### 8.5 Retries

A failed delivery is attempted again after a delay that grows with each attempt:

| Attempt | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|
| After the previous | immediately | 1 min | 5 min | 30 min | 2 h | 6 h | 12 h | 24 h | 24 h |

Nine attempts span about 68 hours, the overview's "about three days". Each delay varies by up to 10% at random, so the deliveries of an outage do not all return at the same second. A `410 Gone` ends the delivery at once: the receiver said the URL is not coming back. After the ninth failure the delivery is `DEAD`.

### 8.6 Disabling

An endpoint records `failing_since`, the time of its first failure after its last success; a success clears it. When an endpoint has been failing for 72 hours and its last 20 attempts all failed, it is disabled with `disabled_reason: failing`: its pending deliveries are canceled, and `WebhookEndpointDisabled` puts a notice on the tenant's live stream and in the audit trail. The two conditions together keep an endpoint that receives one event a week from being disabled by a single bad night, and one that receives thousands from being disabled in minutes.

The tenant enables it again with an update (section 6.3), after fixing its server, and replays what it missed.

### 8.7 Delivery log and replay

Every attempt is recorded: its time, the duration, the status code or the failure reason (`timeout`, `connection_refused`, `address_refused`, `tls_error`, `redirect`), and at most 1 KiB of the response body. The log lists an endpoint's deliveries, newest first, filterable by status and event type, each with its attempts.

A replay sends one delivery again, now: it adds an attempt to it, with the same event ID and body and a fresh timestamp and signature. A dead or succeeded delivery can be replayed, at most 10 times a day each; replaying does not change the schedule of a delivery that is still pending. Replays are how a tenant recovers from a fixed outage, so they work on a disabled endpoint's log too, once it is enabled again.

The response body is the tenant's server talking. It is stored as text, shown escaped, never interpreted and never followed.

### 8.8 Retention

Events, deliveries and attempts are kept for 30 days, then deleted by a job of `webhooks`. The audit trail, not the delivery log, is the permanent record.

## 9. Audit trail

### 9.1 Records

```
audit.records
  id              uuid primary key
  tenant_id       uuid not null      -- the platform tenant for platform actions
  sequence        bigint not null    -- 1, 2, 3 … within the chain
  occurred_at     timestamptz not null
  actor_type      text not null      -- MEMBER | API_KEY | SYSTEM (CHECK)
  actor_id        text not null
  action          text not null      -- such as api_key.issued, charge.refunded
  resource_type   text not null
  resource_id     text not null
  correlation_id  text not null
  event_id        uuid not null unique
  details         text not null      -- canonical JSON (section 9.2)
  previous_hash   bytea not null     -- 32 zero bytes for the first record of a chain
  hash            bytea not null
  unique (tenant_id, sequence)
```

- Each tenant has its own chain; actions of the platform itself go to the platform tenant's chain (tenancy design, section 5.5). Records of one tenant never wait on another's, and a tenant's chain can be verified, and one day exported, without the others.
- `details` holds the facts of the action, such as a key's name and scopes or a refund's amount, never a secret and never personal data.
- `event_id` is unique, so a duplicate event finds its record and adds nothing (section 4.4).

### 9.2 The chain

A record's hash is SHA-256 over the previous record's hash and the record's fields, each encoded as its length and its UTF-8 bytes, in the column order above. Length prefixes make the encoding unambiguous: no two different records produce the same bytes. `details` is stored as the exact text that was hashed, not as `jsonb`, which would reorder its keys; it is rendered canonically once, with sorted keys and no insignificant whitespace.

Appending takes the chain's head: `audit.chain_heads` holds each chain's last sequence and hash, and the listener locks its chain's row (`SELECT … FOR UPDATE`), computes the new record, inserts it and moves the head, in one transaction. Appends to one chain are serialized; appends to different chains are not.

`axion_audit` may insert records and read them, never update or delete them; it updates only `chain_heads`. No role but the owner can change a record, and the chain shows it if the owner does.

### 9.3 Verification

`AuditApi.verify(chain)` walks a chain in sequence order, recomputes every hash and compares it, and checks that sequences have no gaps and that the head matches the last record. A changed record breaks its own hash; a deleted one leaves a gap; a reordered one breaks the next hash.

A job verifies every chain each night, as `axion_audit_checks`, and publishes the result (`axion_audit_chain_verified`, 1 or 0 per chain) and each head's hash in the log. Deleting the last records of a chain together with its head is the one change the chain alone cannot show; the heads written to the log each night, outside the database, are the anchor it is checked against.

### 9.4 What is audited

The domain events marked in section 3.3: key, member and endpoint changes, settlements, flagged payments and refunds. The rule for a new event is the question an investigator would ask: who gave access, who moved money, who changed where the money's notifications go. Operations that only read are not audited in phase 1; revealing masked personal data, which the overview audits (section 4.4), arrives with the screen that reveals it.

## 10. Live notifications

The console's BFF opens one stream per member session and tenant:

```
GET /internal/tenants/{tenant_id}/events/stream
Accept: text/event-stream
Last-Event-ID: evt_01jdr3b6f7g8h9j0k1m2n3p4q5
```

```
id: evt_01jdr4c7…
event: charge.paid
data: {"specversion":"1.0","id":"evt_01jdr4c7…","type":"charge.paid",…}
```

- Each message is the public event of section 5.1, rendered by the same catalog, so the console writes `data.object` into its query cache as it would a response (overview, section 9.6).
- A member receives only the types their permissions allow: charge and payment events need `charges:read`, and `webhook_endpoint.disabled` needs `webhooks:manage`. The tenant comes from the path and the membership, as for every `/internal` route.
- `notifications` keeps, in memory, the streams open on this instance and, per tenant, the last 200 events or 5 minutes of them. A reconnecting client sends `Last-Event-ID` and receives what it missed if it is still kept; otherwise it receives a `reset` message, and the console refetches its queries.
- A comment line every 15 seconds keeps proxies from closing an idle stream. At most 5 streams per member and 200 per tenant; the next is `429`.
- Phase 1 runs one instance of the core, so an in-memory fan-out reaches every stream. With several instances, each would publish the events it handles through PostgreSQL `LISTEN`/`NOTIFY` to the others; that change stays inside `notifications`.

The live stream is a convenience, not a record: a member who missed an event sees its result in the next query. It therefore keeps no table and does not deduplicate beyond the event ID, which the console already uses as the message's identity.

## 11. Schema

The `events`, `webhooks` and `audit` schemas follow the conventions of the other modules: UUIDv7 keys, `timestamptz`, enumerations as `text` with a `CHECK`, a forced tenant policy on every tenant table (tenancy design, section 5.4), and access through `JdbcClient`.

```
events.event_publication                 -- Modulith 2.1's table, unchanged (section 4.2)

webhooks.endpoints
  id                uuid primary key
  tenant_id         uuid not null
  url               text not null            -- https, at most 2,048 characters (CHECK)
  description       text null                -- 1–140 characters (CHECK)
  enabled_events    text[] not null          -- 1–20 types, or {*} (CHECK)
  status            text not null            -- ENABLED | DISABLED (CHECK)
  disabled_reason   text null                -- FAILING | BY_TENANT, set exactly when DISABLED (CHECK)
  failing_since     timestamptz null
  created_at        timestamptz not null
  updated_at        timestamptz not null
  deleted_at        timestamptz null
  index (tenant_id, id)

webhooks.secrets
  id                uuid primary key
  endpoint_id       uuid not null references endpoints
  tenant_id         uuid not null
  ciphertext        bytea not null           -- Tink AEAD, the endpoint's ID as associated data
  expires_at        timestamptz null         -- the end of a rolled secret's grace period
  created_at        timestamptz not null
  index (endpoint_id)

webhooks.events
  id                uuid primary key         -- the domain event's ID
  tenant_id         uuid not null
  type              text not null
  body              bytea not null           -- the CloudEvent as sent (section 5.1)
  occurred_at       timestamptz not null

webhooks.deliveries
  id                uuid primary key
  tenant_id         uuid not null
  endpoint_id       uuid not null references endpoints
  event_id          uuid not null references events
  status            text not null            -- PENDING | SUCCEEDED | DEAD | CANCELED (CHECK)
  attempt_count     integer not null         -- 0 to 9 scheduled attempts (CHECK)
  next_attempt_at   timestamptz null         -- set exactly when PENDING (CHECK)
  leased_until      timestamptz null
  created_at        timestamptz not null
  unique (endpoint_id, event_id)
  index (next_attempt_at) where status = 'PENDING'
  index (endpoint_id, id)

webhooks.attempts
  id                uuid primary key
  tenant_id         uuid not null
  delivery_id       uuid not null references deliveries
  replay            boolean not null
  started_at        timestamptz not null
  duration_ms       integer not null
  status_code       integer null             -- 100–599 (CHECK)
  failure           text null                -- TIMEOUT | CONNECTION_REFUSED | ADDRESS_REFUSED | TLS_ERROR | REDIRECT | …; exactly one of status_code and failure (CHECK)
  response_excerpt  text null                -- at most 1 KiB
  index (delivery_id, started_at)

audit.records                            -- section 9.1
audit.chain_heads
  tenant_id         uuid primary key         -- one chain per tenant (section 9.1)
  sequence          bigint not null
  hash              bytea not null

<schema>.consumed_events                 -- section 4.4, in each consuming module that needs it
```

- `unique (endpoint_id, event_id)` repeats the rule that an event is delivered once to each endpoint, whatever the listener's retries.
- Deleted endpoints keep their row, with `deleted_at`, until their deliveries' retention ends; they receive nothing and are never listed.
- Roles: `axion_events` owns the publications job; `axion_webhooks` manages endpoints, secrets, events and deliveries for the tenant bound; `axion_webhooks_jobs` drains the queue, disables endpoints and removes old rows (section 8.2); `axion_audit` appends (section 9.2); `axion_audit_checks` reads every chain. No role may update or delete an attempt or an audit record.

## 12. API

### 12.1 Modules

```java
// events
public interface EventCatalog {
    Optional<PublicEvent> render(DomainEvent event);    // empty for events with no public type
    Set<String> publicTypes();
}

// webhooks: endpoints and the delivery log, for the controllers of /v1 and /internal
public interface WebhooksApi {
    CreateEndpointResult create(Caller caller, EndpointRequest request);   // Created, InvalidUrl or LimitReached
    …
}

// audit
public interface AuditApi {
    Verification verify(ChainId chain);
}
```

`webhooks`, `audit` and `notifications` receive domain events and nothing else from other modules. Their APIs serve their own controllers and jobs: `WebhooksApi` the endpoint and log operations, `AuditApi` the check.

Public IDs: `evt_` for events, `we_` for webhook endpoints, `whd_` for deliveries, `wha_` for attempts.

### 12.2 HTTP

| Operation | Permission | Notes |
|---|---|---|
| `POST /v1/webhook_endpoints` | `webhooks:manage` | Section 6.3; idempotent; returns the secret once |
| `GET /v1/webhook_endpoints` | `webhooks:manage` | Newest first; a cursor on the endpoint's ID |
| `GET /v1/webhook_endpoints/{id}` | `webhooks:manage` | |
| `PATCH /v1/webhook_endpoints/{id}` | `webhooks:manage` | A JSON merge patch of the four fields of section 6.3 |
| `DELETE /v1/webhook_endpoints/{id}` | `webhooks:manage` | |
| `POST /v1/webhook_endpoints/{id}/secret/roll` | `webhooks:manage` | `grace_period_seconds`, 0 to 604,800; idempotent; returns the new secret once |
| `GET /v1/webhook_endpoints/{id}/deliveries` | `webhooks:manage` | Newest first; filters on `status` and `type` |
| `GET /v1/webhook_endpoints/{id}/deliveries/{delivery_id}` | `webhooks:manage` | With its attempts |
| `POST /v1/webhook_endpoints/{id}/deliveries/{delivery_id}/replay` | `webhooks:manage` | Idempotent; `202 Accepted`, the attempt follows in the log |
| `GET /internal/tenants/{tenant_id}/events/stream` | `@NoPermissionRequired` | Section 10; types filtered by the member's permissions |

- `/internal/tenants/{tenant_id}/webhook_endpoints/…` mirrors each `/v1` operation for the console; creating, updating, deleting and rolling there need step-up.
- Another tenant's endpoint or delivery is `404`, like an unknown one; the isolation harness covers every operation.
- Every `POST` takes `Idempotency-Key`, as the lint requires; `PATCH` and `DELETE` are idempotent by their nature.
- The `webhooks` section of `v1.yaml` describes each public event (section 5.2).

### 12.3 Observability

Metrics: `axion_events_publications_incomplete`, `axion_events_publication_oldest_incomplete_seconds`, `axion_events_publications_failed`, `axion_webhooks_attempts_total{result}`, `axion_webhooks_attempt_duration_seconds`, `axion_webhooks_queue_oldest_due_seconds`, `axion_webhooks_endpoints_disabled_total`, `axion_audit_chain_verified{chain}`, `axion_notifications_streams`. Alerts: any failed publication; the oldest due delivery older than 5 minutes; a chain that fails verification.

Logs carry the event's, endpoint's, delivery's and attempt's IDs and the correlation ID of the event; never a URL's path or query, which may hold a tenant's token, never a secret, never a body.

## 13. Testing

| Suite | What it proves | PR |
|---|---|---|
| Event publication (Modulith `Scenario`) | Each change of the catalog publishes its event in its transaction; a rolled-back change publishes nothing; every event serializes and reads back | 1.44a |
| Registry recovery | An event whose listener fails is resubmitted with backoff and handled once it succeeds; one whose process stops after the commit is handled after the restart | 1.44b |
| Idempotent consumers | The same event handed twice, also concurrently, has one effect | 1.44b |
| Address policy (unit, jqwik) | Every address of each refused range, in every IPv4 and IPv6 form, is refused; addresses outside them are accepted | 1.45a |
| URL validation | Each rule of section 7.1, including names that resolve to a private address | 1.45a |
| Endpoints | The operations and their limits; the secret returned once, encrypted at rest, undecryptable for another endpoint; isolation and step-up | 1.45b |
| Delivery (a local HTTP server) | One delivery per subscribed endpoint; the request of section 8.3; a lease that expires is claimed again; an endpoint at its limit does not slow another; DNS rebinding, a name resolving first to a public and then to a private address, is refused at connection; a redirect is not followed | 1.46a |
| Signing | Standard Webhooks' test vectors; two signatures during a grace period; the body signed as sent | 1.46b |
| Retries and disabling | The schedule of section 8.5 with a fixed clock; `410` ends a delivery; the two conditions of section 8.6 | 1.47a |
| Delivery log and replay | Attempts listed; a replay signed afresh with the same body; the replay limit | 1.47b |
| Public events (contract) | Every type of the catalog rendered and validated against its schema in `v1.yaml`, strictly | 1.46a |
| Audit chain | Appends serialized per chain; a changed, deleted or reordered record fails verification; a duplicate event adds no record | 1.48a |
| Audited events | Each audited event of section 3.3 recorded with its actor | 1.48b |
| Live stream | A member receives their tenant's events within a second, only those their permissions allow; `Last-Event-ID` resumes; the stream limits | 1.49 |
| Fault injection | An endpoint that fails for an hour receives the event once it recovers; the core killed after a settlement's commit delivers its webhook after the restart; settlement's latency does not change while every endpoint times out | 1.47a |

## 14. Trade-offs and alternatives

| Alternative | Why it was rejected |
|---|---|
| **An outbox table of our own** instead of Modulith's registry | The same guarantee with more code to own; Modulith's registry is what the overview fixes, and phase 5 externalizes from it to Kafka without changing publishers |
| **Kafka now** | A broker to run, secure and test for one process that talks to itself; phase 5 adds it when there is a consumer outside the core |
| **Webhooks sent from the listener** | Three days of retries would live in the registry, one slow endpoint would hold a listener thread for each of its events, and there would be no per-endpoint delivery to log or replay |
| **The row lock held during the request** (`SKIP LOCKED` alone) | One open transaction and connection per request in flight, for up to ten seconds each; the lease costs one column and accepts a duplicate after a crash, which receivers already handle |
| **Ordered delivery per resource** | Every later event of a charge would wait behind a failing one; the snapshot in each event makes order unnecessary for receivers that compare state |
| **Thin events** (the ID only) | Smaller and never stale, but every receiver must call back, with an API key it might not otherwise need, and the live stream would cost one request per message |
| **Stripe's `Stripe-Signature` format** | Equally safe, but proprietary; Standard Webhooks is open, has libraries for receivers, and handles rotation the same way |
| **Ed25519 signatures** (Standard Webhooks `v1a`) | Receivers would hold only a public key; but HMAC is what the overview fixes and what most receivers expect. `v1a` can be added beside `v1` without a breaking change |
| **Validating the URL only at registration** | DNS rebinding turns the check into a formality |
| **An egress proxy** (such as Smokescreen) for webhooks | The strongest boundary, enforced outside the core; but one more service to run for a single caller. The resolver check is the same rule, and the proxy is the natural step when `webhooks` is extracted |
| **Hashed signing secrets** | HMAC needs the secret to sign, so it can only be encrypted |
| **One global audit chain** | Proves order across tenants, but serializes every audited action of the platform behind one row, and a tenant's records cannot be verified alone |
| **Audit records as `jsonb`** | Indexable, but PostgreSQL normalizes `jsonb`, so the stored bytes would not be the hashed ones |
| **WebSockets for the console** | Two-way, which the console does not need; SSE reconnects by itself, resumes with `Last-Event-ID` and passes through the BFF as plain HTTP |

## 15. Security considerations

Events and webhooks carry money's news out of the core and requests into other networks. The threats are forged webhooks, notifications sent to the wrong place, the core used to reach what it should not, tampering with the record of who did what, and one tenant's failures used to exhaust the core.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **Spoofing** | Someone sends a tenant's server a forged `charge.paid` | Every request is signed with the endpoint's secret over the ID, timestamp and body; receivers reject old timestamps and repeated IDs; the guide makes verification the first step |
| **Spoofing** | A tenant registers an endpoint or reads a log in another tenant | The tenant comes from the credential; policies are forced on every table; another tenant's resource is `404`; the isolation harness covers each operation |
| **Tampering** | A request is altered on the way | HTTPS only, with certificate verification; the signature covers the body as sent |
| **Tampering** | Someone with database access changes or deletes audit records | Hash chain per tenant; nightly verification; heads written to the log outside the database; no role but the owner may change records |
| **Repudiation** | A tenant says a webhook never arrived, or arrived twice | Every attempt is recorded with its time, status and duration; deliveries keep the event's ID, which the tenant can match |
| **Repudiation** | A member denies redirecting the webhooks | Endpoint changes need step-up and are audited with the member and the correlation ID |
| **Information disclosure** | A webhook URL reaches the metadata service or an internal host (SSRF) | Validation at registration; the resolver checks every address at connection and the connection uses only checked ones; no redirects, no proxy; refusals do not reveal resolved addresses |
| **Information disclosure** | A tenant's response bodies or URLs leak | At most 1 KiB of the response kept, shown escaped, deleted after 30 days; logs carry no URL path or query |
| **Information disclosure** | A signing secret leaks | Shown once; encrypted with Tink, bound to its endpoint; the key-encryption key outside the database; never logged; a Gitleaks rule for its format; rolling with a grace period |
| **Information disclosure** | A member streams another tenant's events, or events beyond their role | The tenant comes from the path through the membership; types are filtered by permissions |
| **Denial of service** | A slow endpoint ties up delivery | Short timeouts, at most 4 deliveries in flight per endpoint, leases instead of open transactions; settlement never waits on a listener |
| **Denial of service** | The core used to flood a third party's URL | A tenant's requests are bounded by its own events, at most 16 endpoints, and replays at most 10 a day each; a failing URL is disabled after three days |
| **Denial of service** | Live streams exhaust connections | Per-member and per-tenant limits; heartbeats; the stream holds no transaction |
| **Elevation of privilege** | A leaked key with `webhooks:manage` points the endpoints at an attacker | The key reads payment news it could already read with `charges:read`, never moves money; endpoint changes are audited; the console shows endpoints and their changes; key and member events never go to webhooks |
| **Elevation of privilege** | A listener acts without a tenant, or with the wrong one | Listeners bind the event's tenant before any query; outside a binding the policies return nothing |
