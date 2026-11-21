# Rails simulator — Design

| | |
|---|---|
| **Status** | Draft |
| **Date** | 2026-11-21 |
| **Scope** | `services/rails-simulator`: what it simulates, the simulated Pix payment, the messages it sends to the core, their delivery and signatures, the chaos controls, its storage, and how it differs from the official specifications. Roadmap PRs 1.29–1.31. |
| **Parent** | [Platform overview](2026-11-17-platform-overview.md), sections 3.1, 3.2, 7.2, 7.6, 12 and 16 |

---

## 1. Problem

Money reaches Finaxis through the Pix rails: a payer's bank sends the payment through the Central Bank's instant payment system (SPI), and the receiving institution learns of it from a message. Finaxis connects to none of the real systems, so something must play their part, or settlement can only be tested by calling the core's own code.

That stand-in decides what the core is tested against. An in-process stub returns the answer the test expects, on time, once. The rails do not: a message arrives late, twice, out of order, or not at all while the network is down, and a timeout says nothing about whether the money moved. The settlement, deduplication and late-payment rules of the overview (sections 7.2 and 7.6) exist for those cases, so they need a counterpart that produces them on demand, over a real network.

The overview fixes the shape: a separate process, orders out and signed asynchronous callbacks in, and a chaos API (sections 3.1, 3.2 and 12). It also accepts that the simulation is a subset of the real rails, and asks for the differences to be documented (section 16). This document decides what the simulator does in phase 1, the contract between it and the core, and every place where it departs from the official specifications.

## 2. Goals and non-goals

**Goals**

- A simulated payer can pay a BR Code issued by the core, and the core learns of the payment only from a signed message, as it would from the rails.
- Every message is delivered at least once, survives a restart of either process, and is retried until the core accepts it.
- No one but the simulator can produce a message the core accepts as authentic, not even someone who reads the core's configuration.
- Each fault the core must survive (latency, failures, duplicates, out-of-order and late messages, an outage) can be produced on demand, deterministically, for one payment or for all of them.
- The messages follow the official ones closely enough that the core's anti-corruption layer (PR 1.38) has the shape it would have against the real rails, and every difference is listed (section 9).

**Non-goals**

- Fidelity beyond what the core's design depends on: SPI time limits, operating windows, participant registration and the full message catalog are not simulated.
- Outgoing money. Payouts (phase 2) send orders to the rails and look up keys in the DICT; their design adds those messages, in the conventions of this one (section 5.4).
- Returning money through the rails. A refund (PR 1.41) is a reversal in the ledger in phase 1; sending it back to the payer (`pacs.004`) comes with the payouts' messages.
- Boleto, Pix Automático, MED, statements and CNAB files. The overview places them in the simulator (section 3.1); each arrives with the feature that needs it.
- Load beyond the core's own targets. The simulator must not be the bottleneck of the load test (PR 1.70), and nothing more.

## 3. Scope

### 3.1 What is simulated

The simulator plays three parties of a real payment, none of which the core may see into:

| Party | Real system | In phase 1 |
|---|---|---|
| **Payer** | A person paying from a banking app | A request to the simulator's payer API with a BR Code (section 4.2) |
| **Payer's institution** | The payer's bank, a Pix participant | Debits the payer, generates the `EndToEndId` and sends the payment, inside the simulator |
| **SPI** | The Central Bank's settlement system | Settles between the participants and notifies the receiver, Finaxis, with a credit notification (section 5.2) |

The core is the receiving participant: an institution with its own settlement account at the Central Bank, which the ledger holds as `platform:settlement` (overview, section 6.3). The tenants' money is the core's liability to them; the rails see only Finaxis.

### 3.2 Participants

Each participant is identified by its ISPB, the eight-digit code the Central Bank assigns. The simulator uses fictitious codes, configured, never those of real institutions:

| Participant | ISPB | Role |
|---|---|---|
| Finaxis | `99990001` | The receiving participant; the only one the simulator delivers to |
| Banco Simulado A, B, C | `99990011`, `99990012`, `99990013` | Payers' institutions |

PR 1.30a checks the codes against the Central Bank's published list of Pix participants and changes any that a real institution uses.

### 3.3 Refinements of the overview

| Overview | This design | Reason |
|---|---|---|
| The simulator is a "fake SPI/DICT, boleto bank" with statements, CNAB and a chaos API (section 3.1) | Phase 1 simulates Pix credits and the chaos API; DICT, boleto, statements and CNAB come with their features | Each part is designed and tested with the feature that depends on it |
| "Orders out, callbacks in" (section 3.2) | Phase 1 has callbacks only; the first orders are payouts' (phase 2), in the conventions of section 5.4 | An incoming Pix charge needs no order: the payer's side starts it |
| "Signed asynchronous callbacks" (section 3.2) | Signed with Ed25519 under HTTP Message Signatures (RFC 9421), not with the HMAC of the webhooks (section 6.3) | The core holds only a public key, so its configuration cannot forge a payment |
| The rails simulator has no storage (section 3.1) | Its own database on the same PostgreSQL server, which the core cannot reach (section 8.3) | A restart must not lose a payment the payer was already debited for |
| "Chaos API" for latency, failures, duplicates, out-of-order and late callbacks (section 12) | Count-based rules for each fault, applied to matching messages, plus an outage switch (section 7) | Tests assert exact outcomes; probabilities are an option, with a seed |

## 4. The simulated Pix payment

### 4.1 Flow

```
payer API ──► simulator: decode BR Code ─► settle ─► record payment + delivery (one transaction)
                                                              │
                                   dispatcher ◄───────────────┘
                                        │  signed credit notification
                                        ▼
                                  core (PR 1.38): verify ─► deduplicate ─► settle the charge
```

A payment is settled in the simulator when it is recorded: the payer's institution has debited the payer and the SPI has moved the money between the settlement accounts. The credit notification tells the core that money it already holds has arrived. The core cannot refuse it, only record it: this is why a payment after a charge expired is credited and flagged, never sent back by the rails (overview, section 7.6).

### 4.2 Paying a BR Code

`POST /pix/payments` takes the BR Code text, as a payer's app would read it from the QR code, and optionally a payer and a message:

```json
{
  "brCode": "00020101021226…6304A1B2",
  "payer": { "name": "Ana Souza", "taxId": "52601815906" },
  "message": "Mensalidade de novembro"
}
```

The simulator decodes the code as the EMV *Merchant-Presented Mode* format of the Central Bank's BR Code manual: tag, length, value, with the CRC16 (CCITT, polynomial `0x1021`, initial value `0xFFFF`) of tag `63` over everything before it. It refuses the code with `422` and a reason when:

- the CRC does not match, or the structure cannot be read;
- the payload format (`00`) is not `01`, the merchant account (`26`) lacks the Pix GUI `br.gov.bcb.pix`, or the currency (`53`) is not `986`, the real;
- the amount (`54`) or the transaction ID (`62`, subfield `05`) is missing, or the transaction ID is `***`.

The last rule is a deviation (section 9). An official dynamic code carries only a URL in tag `26`, subfield `25`; the payer's institution fetches the charge, signed as a JWS, from that location, and finds the amount and the transaction ID there. The simulator reads both from the code instead, so the billing design (PR 1.32) must issue codes that carry them. The transaction ID fits subfield `05`, at most 25 characters. Serving the location is an extension (section 11).

A payer that the request omits is chosen from a fixed list of synthetic people, each with a valid CPF, and given one of the payers' institutions. A message longer than 140 characters is refused, as in the official remittance field.

The simulator does not check whether the charge expired: without the location it cannot know. A payer can therefore pay an expired code, which is how a late payment is produced (section 7.2).

Paying is not idempotent: two requests with one BR Code are two payments, each with its own `EndToEndId`, as when a person pays the same code twice. The core decides what a second payment of a charge means (PR 1.32).

### 4.3 `EndToEndId`

The payer's institution identifies each Pix payment with an `EndToEndId` of 32 characters, as the SPI's message catalog defines it:

```
E 99990011 202611211430 aB3dE5gH7jK
│ │        │            └── 11 letters or digits, unique within the minute
│ │        └── year, month, day, hour and minute of creation, in UTC
│ └── ISPB of the payer's institution
└── always E
```

The last 11 characters are drawn from `SecureRandom`, and the database's unique constraint turns the rare collision into a new draw. The core deduplicates on this value (PR 1.38b), so it is the identity of the payment on both sides.

### 4.4 What the payer API answers

`201 Created`, with the payment's `EndToEndId` and its delivery state, before the core has been told:

```json
{ "endToEndId": "E99990011202611211430aB3dE5gH7jK", "settledAt": "2026-11-21T14:30:12.345Z", "delivery": "pending" }
```

`GET /pix/payments/{endToEndId}` returns the payment and every delivery attempt, so a test or the demo can follow the notification to the core.

## 5. Messages

### 5.1 Conventions

The official messages are ISO 20022 XML. The simulator sends JSON whose fields take their names from the ISO 20022 elements, spelled out in camel case (`EndToEndId` → `endToEndId`, `IntrBkSttlmAmt` → `interbankSettlementAmount`), and their codes from the SPI's catalog (`QRDN`, `CACC`). Only the fields the core uses are sent.

- Amounts are decimal strings with two fraction digits, as in ISO 20022 (`"100.00"`), never JSON numbers; the core's anti-corruption layer turns them into `Money`.
- Times are UTC with milliseconds (`2026-11-21T14:30:12.345Z`).
- Unknown fields must be ignored by the receiver, so a message can gain a field without breaking it.
- Each message has a `messageId`, unique per message, and a `messageType` naming its ISO 20022 counterpart.
- Names and tax IDs are personal data, synthetic but handled as real (overview, section 4.7): the simulator never logs them, and the core encrypts what it keeps.

The contract is an OpenAPI 3.1 document in the simulator's project, `services/rails-simulator/openapi/rails.yaml`, whose `webhooks` section describes each message the simulator sends. It belongs to the simulator, as the SPI's catalog belongs to the Central Bank, and the core's tests read it from there.

### 5.2 Credit notification

The one message of phase 1, the counterpart of an ISO 20022 `pacs.008` credit transfer settled by the SPI:

```json
{
  "messageId": "M99990011aB3dE5gH7jK2mN4pQ6rS8tU",
  "messageType": "pacs.008",
  "creationDateTime": "2026-11-21T14:30:12.401Z",
  "endToEndId": "E99990011202611211430aB3dE5gH7jK",
  "transactionId": "fx7Kq2mP9wR4tY6uA8sD3fG5h",
  "localInstrument": "QRDN",
  "interbankSettlementAmount": { "amount": "100.00", "currency": "BRL" },
  "acceptanceDateTime": "2026-11-21T14:30:12.345Z",
  "debtor": { "name": "Ana Souza", "taxId": "52601815906" },
  "debtorAccount": { "branch": "0001", "number": "123456", "type": "CACC" },
  "debtorAgent": { "ispb": "99990011" },
  "creditorAgent": { "ispb": "99990001" },
  "creditorAccount": { "proxy": "…" },
  "remittanceInformation": "Mensalidade de novembro"
}
```

- `transactionId` is the BR Code's transaction ID, by which the core finds the charge. `creditorAccount.proxy` is the Pix key the code carried, if any.
- `localInstrument` is `QRDN`, a dynamic QR code, for every payment of phase 1.
- `acceptanceDateTime` is when the SPI settled the payment; `creationDateTime`, when the message was written.
- `messageId` is `M`, the ISPB of the sender and 23 random letters or digits, in the layout of the `EndToEndId`.

### 5.3 What the core answers

The core's endpoint, `POST /rails/pix/credits` (PR 1.38a), answers before it settles the charge: it verifies the signature, records the message durably, and answers. The simulator acts on the status alone:

| Answer | Meaning to the simulator | Then |
|---|---|---|
| `2xx` | Accepted, including a duplicate the core already had | The delivery is done |
| `408`, `429`, `5xx`, a timeout, a refused connection | Not received, or not now | Retried (section 6.2); `Retry-After` is honored |
| Any other `4xx` | Refused for good: a bad signature, an unreadable body | Stopped, and kept for manual redelivery |

A refused message is never dropped silently: it stays in the simulator's database, counted in a metric, until an operator redelivers it (section 7.4).

### 5.4 Orders, later

Phase 2 adds the first orders, core to simulator: a payout is a `pacs.008` the core sends, and its result a `pacs.002` the simulator returns. They follow this document's conventions, mirrored: the same JSON naming, the same contract file, signed by the sender's key with the same scheme, delivered at least once, and answered asynchronously by a message, never in the HTTP response. Their design belongs to the payouts design doc.

## 6. Delivery

### 6.1 At least once

The simulator writes the payment and its delivery in one transaction, with the exact bytes of the message. A dispatcher sends what is due:

1. Every 250 ms, claim up to 16 due deliveries with `FOR UPDATE SKIP LOCKED`, and move their `next_attempt_at` 30 seconds ahead, as a lease, in the same transaction.
2. Send each, outside any transaction, with a connect timeout of 1 second and a response timeout of 5.
3. Record the outcome: done, refused, or the next attempt (section 6.2).

A crash between steps 2 and 3 leaves a delivery whose lease expires, so it is sent again, possibly to a core that already received it. That duplicate is deliberate: at least once is the rails' guarantee, and the core's deduplication on `EndToEndId` is what makes the effect single. Retries and duplicates send the stored bytes, so every copy of a message is identical and only its signature differs.

Deliveries are concurrent and unordered, as on the rails: two payments are notified in no guaranteed order, even for one charge.

### 6.2 Retries

A failed attempt is retried after a delay drawn uniformly between zero and `min(5 minutes, 1 second × 2^(attempt − 1))`, the "full jitter" of exponential backoff, or after the core's `Retry-After` if it is longer. After 24 hours without success the delivery is marked `failed`, counted, and kept for manual redelivery. An outage of the core of an hour therefore drains on its own once the core returns, a few minutes at most after it.

### 6.3 Signatures

Each request the simulator sends is signed under HTTP Message Signatures (RFC 9421) with Ed25519 (RFC 8032), over a digest of its body (RFC 9530):

```
POST /rails/pix/credits HTTP/1.1
Content-Type: application/json
Content-Digest: sha-256=:<SHA-256 of the body, base64>:
Signature-Input: rails=("@method" "@path" "content-type" "content-digest");created=1795271412;keyid="rails-2026-11";alg="ed25519"
Signature: rails=:<64-byte Ed25519 signature, base64>:
```

- The signature covers the method, the path, the content type and the digest of the body, so neither the message nor where it was sent can change without breaking it. The host is left out because proxies may rewrite it.
- `created` is the moment of signing, and each attempt is signed afresh. The core refuses a signature more than 5 minutes old or more than 1 minute in the future, so a captured request cannot be replayed later; within the window a replay is a duplicate, and deduplication absorbs it.
- `keyid` names the key, so the core can verify messages signed by the old key and the new one during a rotation.
- Java supports Ed25519 since version 15, and an RFC 9421 signature over four components is a short canonical string: neither side needs a library. RFC 9421's own Ed25519 example (appendix B.2.6) is the known vector for both.

The core's verification is PR 1.38a's: the digest first, then the signature, then the time window, and only then the body is parsed. A failure is `401` with no detail.

### 6.4 Keys

The simulator's private key is configuration, never in the repository: locally, a `just` recipe generates the pair and writes both halves to `infra/compose/.env`; in tests, each run generates its own. The core is configured with public keys only, by `keyid`, so reading its configuration, its logs or its database gives nothing that can sign. A rotation adds the new public key to the core, moves the simulator to the new private key, and removes the old public key once no delivery signed with it can still be retried.

## 7. Chaos controls

### 7.1 Rules

The chaos API changes how the simulator delivers, not what it settles: a payment always happened, and every fault only changes how the core hears of it. A fault is a rule:

```json
{ "fault": "duplicate", "copies": 2, "match": { "transactionId": "fx7Kq2mP9wR4tY6uA8sD3fG5h" }, "times": 1 }
```

- `match` selects deliveries by `endToEndId`, by `transactionId`, or all of them when empty.
- `times` applies the rule to the next *n* matching deliveries, then retires it. Alternatively, `probability` (0 to 1) with a `seed` applies it to each match by chance, until it is removed, for game days and load tests. Tests use `times`, so their outcome is exact.
- Rules are held in memory and listed, removed one by one or all at once. A restart clears them: no fault outlives the session that set it.
- Each delivery records the faults applied to it, so a test asserts what happened from the simulator's side as well as the core's.

### 7.2 Faults

| Fault | Parameters | Effect | What it tests in the core |
|---|---|---|---|
| `latency` | `min`, `max` | Each matching attempt waits a random time in the range before it is sent | Slow rails; timeouts on the callback path |
| `delay` | `duration` | The first attempt is postponed by the duration | A late notification: with a code paid after its expiry, the late payment (PR 1.40) |
| `fail` | — | The attempt is not sent and counts as failed, then retried | Recovery through retries |
| `lost_response` | — | The attempt is sent, its answer discarded, and it counts as failed | A message the core received and committed, sent again: deduplication under the lost-acknowledgment case |
| `duplicate` | `copies` | The delivery is sent `copies` more times, each its own delivery, with identical bytes | Duplicate notifications post once |
| `reorder` | `count` | The next `count` matching deliveries are held, then released together in the reverse of their creation order | Two payments of one charge, notified in the opposite order |

`delay`, `duplicate` and `reorder` apply when a delivery is created; `latency`, `fail` and `lost_response`, at each attempt.

### 7.3 Outage

`PUT /chaos/outage`, with a duration or until removed, takes the rails down: the payer API answers `503`, as a payer's bank does when the SPI is unavailable, and no delivery is attempted. Deliveries already due wait, and when the outage ends they drain with the dispatcher's usual concurrency, which produces the burst of the game day (overview, section 18.2).

### 7.4 Operator API

| Operation | Purpose |
|---|---|
| `POST /pix/payments`, `GET /pix/payments/{endToEndId}` | Pay a BR Code; follow a payment and its deliveries (section 4) |
| `POST /deliveries/{id}/redeliver` | Send a refused or failed delivery again, with its stored bytes, as a new attempt |
| `GET`, `POST`, `DELETE /chaos/rules`, `DELETE /chaos/rules/{id}` | List, add and remove fault rules |
| `PUT`, `DELETE /chaos/outage` | Start and end an outage |

The operator API is contract-first, in `services/rails-simulator/openapi/simulator.yaml`, beside the message contract.

## 8. Service

### 8.1 The process

`services/rails-simulator` is its own Gradle project: Java 25 and Spring Boot, the core's quality gates (Spotless, Error Prone, NullAway, zero warnings, ArchUnit) and its conventions (an injected `Clock`, JSON logs, `JdbcClient`). It shares no code with the core. The rails are another organization's system, and a shared library would couple the core to its own counterpart; it would also let one bug hide on both sides. The simulator decodes BR Codes with its own reader, so a code the core's encoder gets wrong is refused instead of read back by the same mistake.

Its packages follow what it simulates: `payer` (the payer API and BR Code reading), `spi` (settlement and messages), `delivery` (the dispatcher and signatures) and `chaos`.

### 8.2 Network

| Port | Serves | Reached by |
|---|---|---|
| `8090` | The operator API and the health endpoint | Tests, the core's fault-injection suite, the console's demo mode through its BFF, a developer |

The simulator is never exposed publicly. In Compose its port is bound to `127.0.0.1`, as the database's is; in the public demo, the BFF calls it server-side for the "the student pays" scene (demo script, scene 4), and the browser never reaches it. The operator API requires a bearer token from configuration, compared in constant time. The core's URL is configuration as well: locally the core runs on the host, and Compose maps `host.docker.internal` to it.

### 8.3 Storage

A database of its own, `rails_simulator`, on the PostgreSQL server of Compose, with its own login role, as Keycloak's (ADR 0005). The core's roles have no `CONNECT` on it, and the simulator's role none on the core's database: the two systems share a server, not data. Migrations are Flyway's, in the simulator's project.

```
payments
  end_to_end_id        char(32) primary key   -- format of section 4.3 (CHECK)
  transaction_id       text not null          -- 1–25 letters or digits (CHECK)
  amount_minor         bigint not null        -- > 0 (CHECK)
  debtor_ispb          char(8) not null
  br_code              text not null
  settled_at           timestamptz not null

deliveries
  id                   uuid primary key
  end_to_end_id        char(32) not null references payments
  kind                 text not null          -- original | duplicate | redelivery (CHECK)
  message              bytea not null         -- the exact bytes sent on every attempt
  status               text not null          -- pending | delivered | refused | failed (CHECK)
  attempts             integer not null
  next_attempt_at      timestamptz null       -- null once delivered, refused or failed
  last_outcome         text null              -- the last status code or error
  faults               jsonb not null         -- chaos faults applied (section 7.1)
  created_at           timestamptz not null
  index (next_attempt_at) where status = 'pending'
```

The payer's personal data lives only inside `message`, the bytes the core receives; the simulator queries nothing by it.

### 8.4 Observability

Metrics: `rails_sim_payments_total`, `rails_sim_delivery_attempts_total{outcome}` (`delivered`, `retried`, `refused`, `failed`), `rails_sim_deliveries_pending`, and `rails_sim_faults_applied_total{fault}`. Logs carry the `EndToEndId` and the `messageId`, never a name, a tax ID or a Pix key; each delivery sends the `messageId` as its `X-Correlation-Id`, so a notification is traced across both processes.

## 9. Deviations from the official specifications

The references are the Central Bank's *Regulamento Pix* (BCB Resolution No. 1 of 2020), the *Manual de Padrões para Iniciação do Pix* (BR Code), the *Catálogo de Mensagens e de Serviços do SPI* and the ISO 20022 `pacs` messages.

| Aspect | Official | Simulator | Why it is acceptable |
|---|---|---|---|
| Network | The RSFN, the financial system's private network, with mutual TLS and ICP-Brasil certificates | HTTP on the local network, without TLS | Authenticity rests on the message signature (section 6.3); mTLS is an extension (section 11) |
| Message signature | XML digital signature with the participant's ICP-Brasil certificate | RFC 9421 with Ed25519 | Same property, a signature only the sender can make, at a fraction of the cost |
| Format | ISO 20022 XML, validated against the SPI's schemas | JSON with ISO 20022 names, only the fields the core uses (section 5.1) | The anti-corruption layer translates either; the core's domain never sees the format |
| Delivery to the receiver | The receiver retrieves its messages from the SPI's messaging interface by polling | The simulator pushes them to the core | The overview's shape, and it exercises the signed inbound endpoint the core needs in any case |
| Acceptance by the receiver | The SPI asks the receiver to accept or reject the credit (`pacs.002`) before it settles, within time limits | The payment settles in the simulator; the core is notified once it has, and cannot reject | Billing's rule already credits every payment that arrives, late ones included (overview, section 7.6) |
| Dynamic BR Code | Tag `26` carries a location URL; the payer's institution fetches the charge as a signed JWS | The amount (`54`) and the transaction ID (`62-05`) are read from the code (section 4.2) | The core issues the codes it is tested with; serving the location is an extension |
| Expiry | The payer's institution reads the charge's status and expiry from the location | Not checked: an expired code can be paid | It is how late payments are produced on purpose |
| DICT | The payer's institution resolves a Pix key to an account | Not simulated: every payment is credited to Finaxis | Phase 1 has no outgoing payments; payouts add the lookups |
| Settlement accounts | Each participant's balance at the Central Bank, and its statement | Not kept | Reconciliation (phase 4) adds the statements |
| Time limits | The Pix time limits for each step | None; the `latency` and `delay` faults produce slowness on demand | Tests need slowness on demand, not a schedule |
| Participants | Registered institutions and their ISPBs | Four fictitious ISPBs (section 3.2) | No real institution is named or implied |
| Other products | Pix Automático, MED, Pix Saque and Troco, boleto | Not simulated | Each comes with its feature |

A later design that adds a message lists its own deviations in the same form.

## 10. Testing

| Suite | What it proves | PR |
|---|---|---|
| Unit | The BR Code reader accepts the examples of the BCB manual and refuses each rule of section 4.2; the `EndToEndId` always has the format of section 4.3 (jqwik property); backoff delays stay within their bounds | 1.30a, 1.30b |
| Signature | The signer reproduces the Ed25519 vector of RFC 9421, appendix B.2.6; a change to the body, the path or the method breaks the signature | 1.30b |
| Storage (Testcontainers) | The constraints of section 8.3; a payment and its delivery commit together or not at all | 1.30a |
| Delivery (Testcontainers, stub core) | One payment, one delivery; `5xx` and timeouts retried with backoff, `Retry-After` honored; other `4xx` stop; a delivery pending at a restart is sent after it; every attempt sends the same bytes and validates against `rails.yaml` | 1.30b |
| Chaos | Each fault of section 7.2, with `times`, has exactly its effect on the matching deliveries and none on the others; an outage holds every delivery and the backlog drains when it ends | 1.31 |
| Fault injection (core) | The core's suite runs the simulator's image with Testcontainers and drives it through the chaos API: duplicates post once, late payments are credited and flagged, a mid-settlement failure leaves nothing half-posted | 1.42 |

## 11. Trade-offs and alternatives

| Alternative | Why it was rejected |
|---|---|
| **Stubs inside the core's tests** (WireMock, a fake adapter) | No real network boundary: no real timeouts, no outage to survive, no game day. The overview makes the simulator a process for this reason (section 3.1) |
| **State in memory** | A restart would lose notifications for payments already settled: the payer debited, the core never told. Recovering from an outage is a goal, so the outage must not destroy the evidence |
| **HMAC-SHA256**, as the webhooks | Both sides would hold the secret, so anyone who reads the core's configuration could forge a payment. With Ed25519 the core holds only a public key |
| **Mutual TLS** in addition | Closer to the RSFN, but it authenticates the connection, not the message, and costs a certificate authority in Compose. The signature is required in any case; mTLS can be added later without changing the messages |
| **A detached JWS** over the body | Standard, but it covers the body only; RFC 9421 also binds the method and path |
| **ISO 20022 XML** | Most faithful, but it needs the SPI's schemas, XML signatures and a mapping layer on both sides, for no property the core's design depends on |
| **Polling, as the SPI's messaging interface** | More faithful, but the core would need a poller and the simulator a stream protocol. Push keeps the overview's shape; polling can replace it behind the anti-corruption layer |
| **Serving the BR Code location from the core** | Faithful to dynamic codes, but it adds a public endpoint and a JWS signing key to billing before any payment works. It is the first extension if fidelity is pursued |
| **A shared library** with the core (`Money`, `Cpf`, the BR Code) | Couples the core to its counterpart, and one bug would agree with itself on both sides |
| **Random faults only** | Tests would assert probabilities; count-based rules make every outcome exact, and probabilities remain for game days |
| **Toxiproxy** for network faults | Produces latency and broken connections, but not duplicates, reordering or late messages, which are message-level. It may complement the chaos API later |

## 12. Security considerations

The simulator is a test system, but the core treats it as the rails: a message it accepts moves money in the ledger. The threats are a forged or altered credit, a replayed one, and the chaos API used to disrupt or mislead.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **Spoofing** | Someone on the network sends the core a credit notification for a payment that never happened | Ed25519 signature under RFC 9421, verified before the body is read (section 6.3); the private key exists only in the simulator's configuration; the core's endpoint is not routed by the public proxy |
| **Spoofing** | A leak of the core's configuration is used to sign a forged credit | The core holds public keys only (section 6.4) |
| **Tampering** | The amount, the transaction ID or the target of a message is changed in transit | The signature covers the body's digest, the method and the path |
| **Repudiation** | A payment is disputed: was it sent, and how often? | The simulator keeps every message, attempt, outcome and fault applied; the core records the `messageId`, the `EndToEndId` and the `keyid` of what it accepted |
| **Replay** | A captured notification is sent again to credit twice | A signature older than 5 minutes is refused; within the window, the core deduplicates by `EndToEndId` |
| **Information disclosure** | Payers' names and tax IDs cross the network or reach logs | All data is synthetic; on a local network without TLS (section 9). Neither process logs personal data; the simulator stores it only inside the message |
| **Denial of service** | The chaos API or the payer API is used to stop payments or flood the core | Both require the operator token and are bound to localhost or reached server-side by the BFF; the core's endpoint caps bodies and rate-limits the simulator |
| **Elevation of privilege** | The simulator's database role reads or changes the core's data | Separate databases and roles: neither side can connect to the other's |

## 13. Delivery

| PR | Delivers | Sections |
|---|---|---|
| 1.29 | `services/rails-simulator`: Gradle project, Spring Boot skeleton with the core's quality gates, health endpoint, Compose service, CI build and container image | 8.1, 8.2 |
| 1.30a | The payer API and BR Code reader, `EndToEndId`, the payments table and the simulator's database; `simulator.yaml` | 3.2, 4, 8.3 |
| 1.30b | Credit notifications: the deliveries table, the dispatcher, retries, RFC 9421 signatures, key generation; `rails.yaml` | 5, 6, 8.4 |
| 1.31 | The chaos API: fault rules and the outage switch | 7 |

The core's side, the signed endpoint and the translation into billing commands, is PR 1.38a and 1.38b; the fault-injection suite is PR 1.42.

## 14. Decisions from review

1. **The simulator reads the amount and the transaction ID from the BR Code.** It needs no state about charges, and the core stays the only authority over them; serving the location as a signed JWS is the first extension if fidelity is pursued (sections 4.2 and 9).
2. **Callbacks are signed with Ed25519 under RFC 9421.** An asymmetric key means the core holds nothing that can sign, and the signature binds the method and path as well as the body (section 6.3).
3. **Messages are JSON with ISO 20022 names.** The anti-corruption layer has the shape it would have against the real rails, without XML schemas and signatures that no part of the core's design depends on (section 5.1).
4. **The simulator has its own database.** A restart never loses a payment the payer was debited for, and the outage game day recovers from the simulator's own records (section 8.3).
