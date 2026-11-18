# Demo script — phase 1

The three-minute video that closes phase 1 (roadmap item 1.74). It defines what phase 1 must show; every phase 1 pull request is built toward one of its scenes. A feature that appears in no scene needs a reason to be in phase 1.

The story is the phase 1 slice of the reference scenario (overview design, section 2.2): an online course platform charges a student by Pix, the payment settles, the ledger records it, the tenant's system is notified and the console updates live. Then the system is pushed off the happy path and nothing breaks.

## Setting

| Element | Value |
|---|---|
| Tenant | **Escola Lumen**, a fictional online course platform, in its `test` environment |
| Payer | A synthetic student from the data generator (roadmap item 1.66); their CPF never appears on screen |
| Charge | R$ 100.00, Pix with a dynamic QR code (BR Code), expiring in 30 minutes |
| Environment | The local Compose environment, seeded with a few months of synthetic history so the dashboard is not empty |
| Tenant's system | A sample webhook receiver run by the demo, standing in for the school's backend |
| Screen | Split view: terminal on the left, console on the right; console in English, light theme; 1920 × 1080 |

All money, payment rails and data are simulated, and the video says so in its first scene.

## Scenes

| # | Time | Scene | On screen | What it proves |
|---|---|---|---|---|
| 1 | 0:00–0:15 | Opening | Title, one-line positioning, the disclaimer that everything is simulated | Honest framing |
| 2 | 0:15–0:40 | Create a charge | `curl` `POST /v1/charges` with an API key and an `Idempotency-Key`; `201` with the `ch_` ID, status `active` and the BR Code. The same request is sent again and returns the same response, byte for byte | Contract-first API, API key authentication, idempotency |
| 3 | 0:40–1:00 | The charge appears | The console's charge list shows the new charge without a reload; its detail shows the QR code and a timeline with one event, `charge.created` | Live updates over SSE, console design |
| 4 | 1:00–1:25 | The student pays | The simulated payer pays the QR code through the rails simulator; in the console the status turns `paid` and the dashboard balance rises by R$ 100.00, both live | Asynchronous rails integration, signed callbacks, settlement |
| 5 | 1:25–1:50 | The ledger | The charge timeline shows one entry: debit `platform:settlement` R$ 100.00, credit the school's `available` account R$ 100.00; the entry balances and links back to the charge | Double-entry ledger, settlement and posting in one transaction |
| 6 | 1:50–2:10 | The school is notified | The sample receiver prints the `charge.paid` webhook and verifies its HMAC signature; the console's delivery log shows the attempt with `200` | Outbox, signed webhooks, delivery log |
| 7 | 2:10–2:35 | Duplicates | Through the chaos API, the simulator delivers the same payment callback three times; the ledger still holds one entry and the balance is unchanged | Deduplication by `EndToEndId`, single effect |
| 8 | 2:35–2:50 | A tenant endpoint fails | The receiver is stopped; the next webhook fails and is retried with backoff; the receiver is started again and the delivery is replayed from the console | Webhook reliability, manual replay |
| 9 | 2:50–3:00 | Closing | Test suites (`financial-integrity`, tenant isolation, fault injection) green, the published k6 numbers, links to the repository and the public demo | Claims backed by evidence |

Phase 1 charges no platform fee, so the settlement entry in scene 5 has two postings. Fees and split, with the three-way entry of the overview design (section 6.4), arrive in phase 2.

## What each scene needs

The roadmap items a scene depends on. A scene is ready to record when all of its items are merged.

| Scene | Roadmap items |
|---|---|
| 2 — Create a charge | 1.20–1.21 API keys, 1.26 idempotency keys, 1.33–1.35 charge domain, BR Code and creation |
| 3 — The charge appears | 1.44 outbox, 1.49 live notifications, 1.53–1.61 console up to live updates |
| 4 — The student pays | 1.29–1.30 rails simulator, 1.38 rails anti-corruption layer, 1.39 settlement, 1.63 dashboard |
| 5 — The ledger | 1.08–1.11 ledger domain, schema, posting and balances, 1.60 charge timeline |
| 6 — The school is notified | 1.45–1.46 webhook endpoints and delivery, 1.64 developer area |
| 7 — Duplicates | 1.31 chaos API, 1.38 deduplication |
| 8 — A tenant endpoint fails | 1.47 webhook reliability, 1.64 delivery log and replay |
| 9 — Closing | 1.23 tenant isolation harness, 1.42 fault-injection suite, 1.67 demo mode, 1.70 load tests |

## Out of scope

The phase 1 video does not show split, sub-accounts, payouts, the mobile app, Pix Automático, boleto, reconciliation or AI features. They belong to later phases and their own videos.

## Recording rules

- Every step is scripted, so the video can be recorded again after any change and shows the same result.
- No cuts inside a scene: what the viewer sees is what the system did.
- The terminal shows the full request and the relevant response headers; secrets, such as the API key and the webhook secret, are masked.
- The GIFs in the README are cut from scenes 2 to 6.
