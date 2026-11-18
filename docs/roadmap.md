# Finaxis — Roadmap

| | |
|---|---|
| **Status** | Approved |
| **Date** | 2026-11-17 |
| **Source** | [Platform overview design](design-docs/2026-11-17-platform-overview.md) |
| **Pace** | 6–8 hours per day, step-by-step mode (author new to Spring) |

This roadmap turns the platform overview into phases, milestones and pull requests. Phases 0 and 1 are listed PR by PR; phase 1b and phase 2 are listed by deliverable and broken into PRs when their design docs are written; phases 3–5 are extensions, summarized only.

---

## 1. Timeline

| Weeks | Phase | Tag | Outcome |
|---|---|---|---|
| 1–2 | **0 — Foundation** | `v0.0.1` | Repository, CI with security gates, Spring Boot skeleton, PostgreSQL, boundary tests |
| 2–8 | **1 — Financial integrity** | `v0.1.0` | Ledger, tenancy, API keys, idempotency, Pix charges, simulator, settlement, webhooks, console, demo |
| **8** | **Milestone — portfolio-ready** | | Charge → settlement → ledger → webhook → live dashboard, proven and public |
| 9–10 | **1b — Mobile "Charge now"** | `v0.1.x` | Expo app with Pix at the counter |
| 11–15 | **2 — Money movement** | `v0.2.0` | Split, sub-accounts, payout saga, biometric maker-checker |
| 16+ | **3–5 — Extensions** | `v0.3.0+` | Only after the phase 1 and 2 exit criteria are met |

Estimates assume the Spring learning curve. Quality is never traded for schedule: if a phase runs late, the timeline moves, not the exit criteria.

---

## 2. Working agreement

- **One PR = one small, complete slice.** Phases 0–1 target about 50–200 changed lines per PR; each commit introduces one concept.
- **Per PR:** short goal statement → commits on a feature branch → PR from the template → explanation to the author (what, components, why, how it works, files, review questions) → author review → fixes as new commits → the author rebases the branch onto `main` locally and merges with a merge commit (keeps every commit signed).
- **Design first:** every block that introduces a significant feature starts with its design doc PR.
- **GitHub:** one milestone per phase, one issue per PR (`Closes #n`).
- **Definition of Done:** tests, updated docs, security checklist, green CI, size limit respected.

---

## 3. Phase 0 — Foundation (weeks 1–2)

### 3.1 Guided setup (no PRs)

| # | Step | Result |
|---|---|---|
| S1 | WSL2 and Ubuntu; Git; JDK 25 (via SDKMAN); Docker Desktop with WSL integration; Node LTS with pnpm (Corepack); uv; just; GitHub CLI | A verified toolchain, checked by a script added in PR 0.01 |
| S2 | GitHub repository `finaxis` (public); `gh auth login`; SSH commit-signing key; Git identity; `main` ruleset; signed initial commit | Signed commits and PRs opened from the CLI |
| S3 | VS Code WSL extension; Claude Code inside Ubuntu; global instructions copied to `~/.claude` | Work continues natively inside Ubuntu |

### 3.2 Pull requests

| PR | Title | Delivers | Key concepts |
|---|---|---|---|
| 0.01 | Repository bootstrap | README skeleton (positioning, disclaimer, AI transparency), Apache 2.0 license, `.gitignore`, `.editorconfig`, `.gitattributes`, toolchain check script | Repository hygiene |
| 0.02 | Engineering process | Project `CLAUDE.md`, `CONTRIBUTING.md`, PR template with security checklist, issue templates | How the team works |
| 0.03 | Design baseline | Platform overview design doc, this roadmap, `docs/` structure with section READMEs | Design-first |
| 0.04 | ADR process | ADR template; ADR-0001 (record decisions); ADR-0002 (modular monolith); ADR-0003 (no card data / PCI scope) | Architecture decision records |
| 0.05 | Commit conventions | Lefthook pre-commit (Gitleaks, formatting), commitlint | Guardrails before code |
| 0.06 | Security baseline in CI | Gitleaks, CodeQL, Dependabot, `SECURITY.md` | Supply-chain security |
| 0.07 | Gradle build | Gradle wrapper, `settings.gradle.kts`, version catalog for `services/core` | Gradle, dependency management |
| 0.08 | Spring Boot skeleton | `Application` class; the app starts and logs | `@SpringBootApplication`, auto-configuration |
| 0.09 | First test | Context-loads test with JUnit 5 and AssertJ | Spring test context |
| 0.10 | Health and configuration | Actuator health endpoint, `application.yml`, profiles | Actuator, profiles, externalized config |
| 0.11 | Code quality gates | Spotless, Error Prone, NullAway, JSpecify | Static analysis, null safety |
| 0.12 | CI for the core | GitHub Actions build and test with Gradle caching; CI added as a required status check in the `main` ruleset | Continuous integration |
| 0.13 | Local infrastructure | Docker Compose with PostgreSQL (profile `core`); `justfile` (`just up`, `just down`) | Containers, Compose profiles |
| 0.14 | Database connection and migrations | Datasource, Flyway baseline migration, tests against a Testcontainers PostgreSQL | Flyway, versioned migrations, testing with real infrastructure |
| 0.15 | — | Merged into 0.14: with a datasource, every application test needs a database | — |
| 0.16 | Structured logging | JSON logs, correlation ID filter | Observability from day one |
| 0.17 | Module boundaries | Spring Modulith verification, ArchUnit rules (pure domain, constructor injection, `Clock`), `shared` module placeholder | Modular monolith enforcement |
| 0.18 | Error handling baseline | RFC 9457 Problem Details, global exception handling | Consistent API errors |
| 0.19 | Security baseline | Spring Security deny-by-default, security headers | Secure by default |
| 0.20 | Contract-first API | OpenAPI skeleton for `/v1` and `/internal`, Spectral lint in CI | Contract-first design |
| 0.21 | Container image | Multi-stage Dockerfile, non-root user, Trivy scan in CI | Secure images |
| 0.22 | Engineering standards and C4 context | `docs/architecture/engineering-standards.md`, C4 context diagram; tag `v0.0.1` | Documentation as a product |

---

## 4. Phase 1 — Financial integrity (weeks 2–8)

### 4.1 Block A — Domain foundations

| PR | Title | Delivers |
|---|---|---|
| 1.01 | Demo video script | The 3-minute scenario phase 1 must show (`docs/architecture/demo-script.md`) |
| 1.02 | `Money` value object | Minor units, currency, arithmetic, HALF_EVEN rounding, unit tests |
| 1.03 | `Money` property tests | jqwik properties (associativity, no precision loss, allocation sums) |
| 1.04 | Identifiers | UUIDv7 generation, prefixed public IDs (`ch_`, `acct_`…) |
| 1.05 | Time and calendar | Injected `Clock`, business-day and bank-holiday calendar, `America/Sao_Paulo` business dates |
| 1.06 | Brazilian documents | `Cpf`, `Cnpj` (including alphanumeric), `PixKey` value objects with validation |

### 4.2 Block B — Ledger

| PR | Title | Delivers |
|---|---|---|
| 1.07 | Ledger design doc | Model, invariants I1–I7, concurrency, hot accounts, security considerations; ADR on `JdbcClient` |
| 1.08 | Ledger domain | `Account`, `Entry`, `Posting`; balanced-entry invariant (pure Java) |
| 1.09 | Ledger schema | Tables, `CHECK`/`UNIQUE` constraints, deferred balance trigger, DB role without UPDATE/DELETE |
| 1.10 | Posting entries | `LedgerApi.post` with `JdbcClient`, idempotent on the source key |
| 1.11 | Balances | Conditional atomic update, `InsufficientFunds` result, fixed lock order |
| 1.12 | Concurrency proof | 100 concurrent debits against one balance; deadlock test |
| 1.13 | Balance verifier | Scheduled recomputation of I6/I7 with alerting |
| 1.14 | Ledger queries | `balanceOf`, cursor-paginated `history` |
| 1.15 | Mutation testing | PIT on the ledger module with threshold in CI |

### 4.3 Block C — Tenancy and access

| PR | Title | Delivers |
|---|---|---|
| 1.16 | Tenancy and access design doc | Tenant model, roles, API keys, RLS, threat model |
| 1.17 | Tenancy domain and schema | Tenants, environments (test/live), members and roles |
| 1.18 | Keycloak | Keycloak in Compose, realm as code, MFA policy |
| 1.19 | Human authentication | Spring Security OAuth2 resource server on `/internal`; Keycloak in Testcontainers |
| 1.20 | API key issuance | Generation, hashing, prefixes, scopes, rotation, revocation |
| 1.21 | API key authentication | Filter for `/v1`; tenant context derived from the credential |
| 1.22 | Row-Level Security | RLS policies and per-module DB roles |
| 1.23 | Tenant isolation harness | A test that walks every OpenAPI operation attempting cross-tenant access |
| 1.24 | Rate limiting | Per-tenant and per-key limits, `429` with `Retry-After` |

### 4.4 Block D — Idempotency

| PR | Title | Delivers |
|---|---|---|
| 1.25 | Idempotency design doc | Insert-first mechanics, conflict semantics, retention |
| 1.26 | Idempotency keys | Table, interceptor, stored responses, `409` and `422` semantics |
| 1.27 | Idempotency proof | 50 parallel repeats yield one effect |

### 4.5 Block E — Rails simulator

| PR | Title | Delivers |
|---|---|---|
| 1.28 | Simulator design doc | Scope, simulated messages, deviations from official specs, chaos controls |
| 1.29 | Simulator skeleton | `services/rails-simulator` Gradle project, Compose service |
| 1.30 | Pix payment simulation | Simulated payer, `EndToEndId`, signed callbacks to the core |
| 1.31 | Chaos API | Latency, failures, duplicates, out-of-order and late callbacks, on demand |

### 4.6 Block F — Billing and settlement

| PR | Title | Delivers |
|---|---|---|
| 1.32 | Billing design doc | Charge lifecycle, settlement transaction, late payments, refunds, security considerations |
| 1.33 | Charge domain | Aggregate and state machine; invalid transitions impossible |
| 1.34 | BR Code | Dynamic QR (EMV) payload generation with CRC16, tests against known vectors |
| 1.35 | Create charge | `POST /v1/charges`, contract-first, idempotent, limits |
| 1.36 | Read charges | `GET /v1/charges` (cursor pagination, filters), `GET /v1/charges/{id}` |
| 1.37 | Basic risk rules | Pre-charge decision: amount and velocity rules |
| 1.38 | Rails anti-corruption layer | Callback endpoint, signature verification, `EndToEndId` deduplication |
| 1.39 | Settlement | One transaction: charge `PAID` + ledger entry + outbox event |
| 1.40 | Late payments | Payment after expiration: credited and flagged for review |
| 1.41 | Refunds | Reversal entries, partial and full refunds |
| 1.42 | Fault-injection suite | Timeouts, duplicates, out-of-order and late callbacks, mid-settlement failure |

### 4.7 Block G — Events, webhooks, audit and notifications

| PR | Title | Delivers |
|---|---|---|
| 1.43 | Events and webhooks design doc | Event catalog, CloudEvents envelope, signing, retries, SSRF defenses |
| 1.44 | Domain events and outbox | Spring Modulith events with the publication registry; idempotent consumers |
| 1.45 | Webhook endpoints | `/v1/webhook_endpoints` with SSRF validation and secret management |
| 1.46 | Webhook delivery | PostgreSQL queue (`SKIP LOCKED`), HMAC-SHA256 with timestamp |
| 1.47 | Webhook reliability | Exponential backoff, endpoint disabling, delivery log, manual replay, dead letters |
| 1.48 | Audit trail | Hash-chained audit records and a verification check |
| 1.49 | Live notifications | Per-tenant SSE stream for the BFF |

### 4.8 Block H — Console

| PR | Title | Delivers |
|---|---|---|
| 1.50 | JavaScript monorepo | pnpm workspace, Turborepo, shared package scaffolding, ESLint boundary rules |
| 1.51 | Design study | `docs/design/principles.md` from studying Mercury and Stripe patterns |
| 1.52 | Design tokens | `packages/tokens`: one source, CSS variables, light and dark themes |
| 1.53 | Next.js skeleton | App Router, Tailwind v4, tokens, theme cookie, security headers and CSP |
| 1.54 | BFF authentication | OIDC with Keycloak, server-side session, `HttpOnly` cookie, CSRF protection |
| 1.55 | Generated API client | `packages/api-client` from OpenAPI with injectable transport |
| 1.56 | Design system primitives | Button, Input, Badge, Card, Dialog, Drawer on Radix; Storybook; axe checks |
| 1.57 | Data table | TanStack Table + Virtual, URL filters with nuqs |
| 1.58 | App shell | Sidebar, top bar, command palette skeleton (cmdk), four-state screen pattern |
| 1.59 | Charges list | Dense, virtualized list with filters and empty/error/loading states |
| 1.60 | Charge detail timeline | Events, ledger postings and webhook attempts; raw JSON drawer |
| 1.61 | Live updates | SSE events written into the TanStack Query cache |
| 1.62 | Charts | visx chart components in the design system (area, bar, sparkline) with accessible tables |
| 1.63 | Dashboard v1 | Balance, inflows over time, recent payments |
| 1.64 | Developer area | API keys (with step-up), webhook endpoints, delivery log and replay |
| 1.65 | Internationalization | pt-BR and en via `packages/i18n`, error-code messages |

### 4.9 Block I — Data, proof and release

| PR | Title | Delivers |
|---|---|---|
| 1.66 | Synthetic data generator | Seeded months of realistic tenants, charges, refunds and failures |
| 1.67 | Demo mode | MSW fixtures from the generator; static deployment to a free host |
| 1.68 | End-to-end tests | Playwright journeys (login, charge, live settlement), axe, visual regression |
| 1.69 | DAST | OWASP ZAP baseline against the Compose environment in CI |
| 1.70 | Load tests | k6 scenarios, hot-account comparison, results and charts in the README |
| 1.71 | Game day 1 | Rails outage and duplicated callbacks at peak; postmortem |
| 1.72 | Game day 2 | Tenant webhook endpoint down for hours; postmortem |
| 1.73 | Phase documentation | Threat model, ASVS mapping, runbooks, `REVIEWERS.md`, README tour |
| 1.74 | Demo video and release | Recorded demo, GIFs; exit criteria checklist; tag `v0.1.0` |

**Milestone — portfolio-ready:** all phase 1 exit criteria met (overview design, section 15.2).

---

## 5. Phase 1b — Mobile "Charge now" (weeks 9–10)

Design doc first; then PRs for: Expo project and development build (Android); shared packages wiring (`api-client`, `flows`, `query`, `tokens`, `i18n`); PKCE login with secure storage; BFF `/api/mobile/*` routes; "Charge now" flow (amount → QR → waiting → paid/expired/failed) with SSE confirmation, sound and haptics; poor-connectivity handling with idempotent retries; push notifications; Maestro end-to-end tests.

---

## 6. Phase 2 — Money movement (weeks 11–15)

Design docs first (split, payouts saga, device-bound approvals). Deliverables:

- **Split and sub-accounts:** recipients, split rules, `SplitPolicy`, multi-leg settlement entries, proportional refunds.
- **Payouts:** batches, holds, orchestrated saga with persisted steps, compensation, `UNKNOWN` resolution via status inquiry.
- **Maker-checker:** approval workflow and role enforcement.
- **Device-bound biometric approval:** key generation in Secure Enclave/Keystore, signed approval payloads, backend verification, step-up.
- **Console:** sub-accounts, payout batches, approval status.
- **Proof:** payout concurrency and saga fault-injection tests, game day 3 with postmortem, updated load tests, docs and demo; tag `v0.2.0`.

---

## 7. Extensions (after phase 2 exit criteria)

| Phase | Summary |
|---|---|
| **3 — Recurring revenue** | Subscriptions, Pix Automático mandates, scheduling and retries, dunning, reporting projections, full cash dashboard |
| **4 — Operations and intelligence** | Reconciliation (statements, CNAB), MED 2.0 cases and fund tracing, AI use cases (docs assistant, assisted reconciliation, "Ask your cash", investigation copilot) with governance documents |
| **5 — Scale and cloud readiness** | Kafka externalization, `webhooks` service extraction, kind + Helm, AWS Terraform (validated, not applied), full observability, optional ML |

---

## 8. Articles

Published after the phase that backs them (overview design, section 18.3): articles 1–11 after phase 1, 12–14 after phase 2, 15–17 after phase 4. Cadence: one every two weeks; full article in English, short LinkedIn post in Portuguese.
