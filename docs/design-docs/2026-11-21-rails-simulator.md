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
