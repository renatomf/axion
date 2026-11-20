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

- **One PR = one complete item, or a slice of it.** An item is split only for a reason (see `CONTRIBUTING.md`, *Pull request size*), never to fit a size; each commit introduces one concept.
- **Per PR:** short goal statement → commits on a feature branch → PR from the template → explanation to the author (what, components, why, how it works, files, review questions) → author review → fixes as new commits → the author rebases the branch onto `main` locally and merges with a merge commit (keeps every commit signed).
- **Design first:** every block that introduces a significant feature starts with its design doc PR.
- **GitHub:** one milestone per phase, one issue per PR (`Closes #n`).
- **Numbering:** a PR takes the number of its roadmap item, `<phase>.<item>` (for example `1.21`, item 21 of phase 1). An item delivered in one PR takes no letter; its issue is titled `PR 1.25: <title>`. An item that is split is delivered in lettered slices, `1.21a`, `1.21b`, `1.21c`, each its own issue and PR, titled `PR 1.21b: <title>`, and the first slice's issue names the reason for the split. In the tables below, each slice has a row (`↳`) under its item, and the row of a slice, or of an item delivered in one PR, gets the link to its pull request when it is merged. Slices not yet merged may be split, merged or renumbered when the plan changes, in particular by the design doc that opens their block; each issue's *Out* section names the slices still to come. A PR that splits, adds or renumbers slices updates their rows in the same PR, so the table always shows the current plan. Every slice belongs to its item's feature: work for another feature goes into that feature's item, never into a slice of this one. An item merged into another keeps its row, marked as merged (see 0.15).
- **Definition of Done:** tests, updated docs, security checklist, green CI, a diff a reviewer can read with care.

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
| ↳ 1.17a | Tenancy schema | Organizations, tenants and members; the platform organization; the tenancy database role; opened as PR 1.17 ([#85](https://github.com/renatomf/finaxis/pull/85)) |
| ↳ 1.17b | Roles and permissions | The role matrix and the permissions each role grants ([#91](https://github.com/renatomf/finaxis/pull/91)) |
| ↳ 1.17c | Tenancy API and organization creation | `TenancyApi`; an organization created with both tenants and its first admin ([#93](https://github.com/renatomf/finaxis/pull/93)) |
| 1.18 | Keycloak | Keycloak in Compose, realm as code, MFA policy |
| 1.19 | Human authentication | Spring Security OAuth2 resource server on `/internal`; Keycloak in Testcontainers |
| ↳ 1.19a | Member token validation | Access tokens from Keycloak validated on `/internal` ([#89](https://github.com/renatomf/finaxis/pull/89)) |
| ↳ 1.19b | Member identity and `/internal/me` | The caller's organizations and tenants ([#95](https://github.com/renatomf/finaxis/pull/95)) |
| ↳ 1.19c | Tenant resolution and permissions | Tenant from the path through the member's memberships; `@RequiresPermission` ([#97](https://github.com/renatomf/finaxis/pull/97)) |
| ↳ 1.19d | Step-up on recent authentication | `@RequiresStepUp` and the RFC 9470 challenge ([#99](https://github.com/renatomf/finaxis/pull/99)) |
| 1.20 | API key issuance | Generation, hashing, prefixes, scopes, rotation, revocation |
| ↳ 1.20a | API key format | Prefix, random part and CRC32 checksum ([#101](https://github.com/renatomf/finaxis/pull/101)) |
| ↳ 1.20b | API key schema | The keys table and the access database role ([#103](https://github.com/renatomf/finaxis/pull/103)) |
| ↳ 1.20c | API key issuance | `AccessApi.issue`: scopes within the issuer's permissions, limit of 10 working keys ([#105](https://github.com/renatomf/finaxis/pull/105)) |
| ↳ 1.20d | API key listing and revocation | `AccessApi.keys` and `AccessApi.revoke` ([#107](https://github.com/renatomf/finaxis/pull/107)) |
| ↳ 1.20e | API key rolling | `AccessApi.roll` with a grace period ([#109](https://github.com/renatomf/finaxis/pull/109)) |
| ↳ 1.20f | API key listing endpoint | `GET /internal/tenants/{tenant_id}/api_keys` ([#111](https://github.com/renatomf/finaxis/pull/111)) |
| ↳ 1.20g | Request validation | `422 validation_failed` with the offending fields, for every request body ([#113](https://github.com/renatomf/finaxis/pull/113)) |
| ↳ 1.20h | API key issuance endpoint | `POST …/api_keys`, the secret returned once ([#115](https://github.com/renatomf/finaxis/pull/115)) |
| ↳ 1.20i | API key revocation endpoint | `POST …/api_keys/{key_id}/revoke` ([#117](https://github.com/renatomf/finaxis/pull/117)) |
| ↳ 1.20j | API key rolling endpoint | `POST …/api_keys/{key_id}/roll` ([#119](https://github.com/renatomf/finaxis/pull/119)) |
| 1.21 | API key authentication | Filter for `/v1`; tenant context derived from the credential |
| ↳ 1.21a | API key authentication | `AccessApi.authenticate` and its outcomes, in the access module ([#121](https://github.com/renatomf/finaxis/pull/121)) |
| ↳ 1.21b | API key authentication on `/v1` | The `/v1` filter chain; one `401` for every rejected key, `403 tenant_inactive` ([#123](https://github.com/renatomf/finaxis/pull/123)) |
| ↳ 1.21c | API key scopes on `/v1` endpoints | `@RequiresPermission` checked against the key's scopes; the endpoint receives the `ApiKeyCaller` ([#127](https://github.com/renatomf/finaxis/pull/127)) |
| ↳ 1.21d | Key last use | `last_used_at`, updated at most once an hour per key by a conditional update ([#129](https://github.com/renatomf/finaxis/pull/129)) |
| ↳ 1.21e | Authentication failure signals | Failure logs with the reason and key ID, never the secret; `finaxis_authentication_failures_total{reason}` ([#131](https://github.com/renatomf/finaxis/pull/131)) |
| 1.22 | Row-Level Security | The tenant and platform tenant bound to every transaction; RLS policies on tenant data; the login role and per-module DB roles |
| ↳ 1.22a | Tenant context | `TenantContext` (`ScopedValue`) bound around the controller method once the caller is established; a transaction listener sets `finaxis.tenant_id` ([#149](https://github.com/renatomf/finaxis/pull/149)) |
| ↳ 1.22b | Tenant policies | `shared.current_tenant()`; RLS on `access.api_keys`; the key lookup and last use through `SECURITY DEFINER` functions owned by `finaxis_access_lookup` ([#151](https://github.com/renatomf/finaxis/pull/151)) |
| ↳ 1.22c | Module roles | Each module's API entry point switches the transaction to its role with `SET LOCAL ROLE`, restoring the caller's on return ([#153](https://github.com/renatomf/finaxis/pull/153)) |
| ↳ 1.22d | Login role | `finaxis_app` with `NOINHERIT`, a member of every module role; migrations run as the owner with credentials of their own; the ledger's commit-time checks run as `finaxis_ledger_checks` ([#155](https://github.com/renatomf/finaxis/pull/155)) |
| ↳ 1.22e | Ledger jobs role | The balance verifier runs as `finaxis_ledger_jobs`, which may only read the ledger ([#157](https://github.com/renatomf/finaxis/pull/157)) |
| ↳ 1.22f | Platform tenant binding | `TenantContext` carries the tenant's environment; the transaction listener sets `finaxis.platform_tenant_id`, the platform tenant of that environment, beside the tenant ([#159](https://github.com/renatomf/finaxis/pull/159)) |
| ↳ 1.22g | Ledger tests bind their tenant | The ledger's tests call it as its callers will, with the tenant bound, so Row-Level Security can be forced on its tables ([#161](https://github.com/renatomf/finaxis/pull/161)) |
| ↳ 1.22h | Ledger policies | `shared.current_platform_tenant()`; the read and write policies of the ledger tables, and read-only ones for `finaxis_ledger_jobs` and `finaxis_ledger_checks`; a source posted by another tenant, whose entry the policies hide, is still reported as such |
| 1.23 | Tenant isolation harness | A test that walks every OpenAPI operation attempting cross-tenant access |
| ↳ 1.23a | Cross-tenant isolation | Operations read from the OpenAPI contracts, each with a fixture or the suite fails; tenant A's credential on tenant B's resources gets `404`, and lists never include them |
| ↳ 1.23b | Environment isolation | A test key never reaches live data |
| 1.24 | Rate limiting | Per-tenant and per-key limits, `429` with `Retry-After` |
| ↳ 1.24a | API key rate limits | Bucket4j buckets per tenant and per key, in a bounded cache, applied after authentication; `429 rate_limited` with `Retry-After` |
| ↳ 1.24b | Member limits and headers | Per-member buckets on `/internal`; `RateLimit-Policy` and `RateLimit` headers |

**Cleanup before 1.22.** The existing code was brought to the [comment standard](architecture/engineering-standards.md#comments-and-documentation) ([#133](https://github.com/renatomf/finaxis/pull/133)), one module per PR: `access` ([#135](https://github.com/renatomf/finaxis/pull/135), [#137](https://github.com/renatomf/finaxis/pull/137)), `tenancy` ([#139](https://github.com/renatomf/finaxis/pull/139)), `ledger` ([#141](https://github.com/renatomf/finaxis/pull/141)), then `shared`, `web` and `observability` ([#143](https://github.com/renatomf/finaxis/pull/143)). Duplicated test helpers and production code were removed ([#145](https://github.com/renatomf/finaxis/pull/145)), and `CONTRIBUTING.md` was trimmed to commands and rules ([#147](https://github.com/renatomf/finaxis/pull/147)). These PRs changed no behavior and take no roadmap number.

### 4.4 Block D — Idempotency

| PR | Title | Delivers |
|---|---|---|
| 1.25 | Idempotency design doc | Insert-first mechanics, conflict semantics, retention |
| 1.26 | Idempotency keys | Table, interceptor, stored responses, `409` and `422` semantics |
| ↳ 1.26a | Idempotency store | The keys table, its constraints and database role; insert-first claim of a key and the stored response; scheduled removal of expired keys |
| ↳ 1.26b | Idempotency interceptor | `Idempotency-Key` on `/v1` writes; a repeat replays the stored response; `409` for a request still in progress, `422` for a key reused with another body |
| 1.27 | Idempotency proof | 50 parallel repeats yield one effect |

### 4.5 Block E — Rails simulator

| PR | Title | Delivers |
|---|---|---|
| 1.28 | Simulator design doc | Scope, simulated messages, deviations from official specs, chaos controls |
| 1.29 | Simulator skeleton | `services/rails-simulator` Gradle project and Spring Boot skeleton, Compose service, CI build and image |
| 1.30 | Pix payment simulation | Simulated payer, `EndToEndId`, signed callbacks to the core |
| ↳ 1.30a | Simulated payer | A payer that pays a BR Code; `EndToEndId` generation |
| ↳ 1.30b | Signed callbacks | Payment callbacks to the core, signed and retried |
| 1.31 | Chaos API | Latency, failures, duplicates, out-of-order and late callbacks, on demand |

### 4.6 Block F — Billing and settlement

| PR | Title | Delivers |
|---|---|---|
| 1.32 | Billing design doc | Charge lifecycle, settlement transaction, late payments, refunds, security considerations |
| 1.33 | Charge domain | Aggregate and state machine; invalid transitions impossible |
| 1.34 | BR Code | Dynamic QR (EMV) payload generation with CRC16, tests against known vectors |
| 1.35 | Create charge | `POST /v1/charges`, contract-first, idempotent, limits |
| ↳ 1.35a | Charge creation | The charges table, its constraints and database role; `BillingApi.createCharge` with its limits |
| ↳ 1.35b | Create charge endpoint | `POST /v1/charges` in `v1.yaml`, then the endpoint, idempotent |
| 1.36 | Read charges | `GET /v1/charges` (cursor pagination, filters by status and date), `GET /v1/charges/{id}` |
| 1.37 | Basic risk rules | Pre-charge decision: amount and velocity rules |
| ↳ 1.37a | Risk rules | `RiskApi` with the amount rule and the velocity rule |
| ↳ 1.37b | Risk before charging | The decision applied to charge creation |
| 1.38 | Rails anti-corruption layer | Callback endpoint, signature verification, `EndToEndId` deduplication |
| ↳ 1.38a | Callback endpoint | The rails callback endpoint and its signature verification |
| ↳ 1.38b | Callback translation | `EndToEndId` deduplication; rails messages translated into billing commands |
| 1.39 | Settlement | One transaction: charge `PAID` + ledger entry + outbox event |
| 1.40 | Late payments | Payment after expiration: credited and flagged for review |
| 1.41 | Refunds | Reversal entries, partial and full refunds |
| ↳ 1.41a | Refunds | Refunds, their states and table; reversal entries for full and partial refunds within the paid amount |
| ↳ 1.41b | Refund endpoint | `POST /v1/charges/{id}/refunds`, idempotent |
| 1.42 | Fault-injection suite | Timeouts, duplicates, out-of-order and late callbacks, mid-settlement failure |

### 4.7 Block G — Events, webhooks, audit and notifications

| PR | Title | Delivers |
|---|---|---|
| 1.43 | Events and webhooks design doc | Event catalog, CloudEvents envelope, signing, retries, SSRF defenses |
| 1.44 | Domain events and outbox | Spring Modulith events with the publication registry; idempotent consumers |
| ↳ 1.44a | Domain events | Spring Modulith events with the JDBC publication registry; settlement and API key events published through it |
| ↳ 1.44b | Idempotent consumers | Each event handled once per consumer |
| 1.45 | Webhook endpoints | `/v1/webhook_endpoints` with SSRF validation and secret management |
| ↳ 1.45a | SSRF validation | URLs to private, loopback and metadata addresses refused |
| ↳ 1.45b | Webhook endpoints | Endpoints, their events and table; signing secret generation and rotation; `/v1/webhook_endpoints` |
| 1.46 | Webhook delivery | PostgreSQL queue (`SKIP LOCKED`), HMAC-SHA256 with timestamp |
| ↳ 1.46a | Delivery queue | PostgreSQL queue drained with `SKIP LOCKED`; HTTP delivery with timeouts and the SSRF check at connection |
| ↳ 1.46b | Webhook signing | HMAC-SHA256 with timestamp |
| 1.47 | Webhook reliability | Exponential backoff, endpoint disabling, delivery log, manual replay, dead letters |
| ↳ 1.47a | Retries and disabling | Exponential backoff; endpoints that keep failing are disabled |
| ↳ 1.47b | Delivery log and replay | Every attempt recorded and listed; manual replay; dead letters after the last attempt |
| 1.48 | Audit trail | Hash-chained audit records and a verification check |
| ↳ 1.48a | Audit chain | Hash-chained audit table and the check that the chain is intact |
| ↳ 1.48b | Audited events | Key, member and settlement events recorded |
| 1.49 | Live notifications | Per-tenant SSE stream for the BFF |

### 4.8 Block H — Console

| PR | Title | Delivers |
|---|---|---|
| 1.50 | JavaScript monorepo | pnpm workspace, Turborepo, shared package scaffolding, ESLint boundary rules |
| 1.51 | Design study | `docs/design/principles.md` from studying Mercury and Stripe patterns |
| 1.52 | Design tokens | `packages/tokens`: one source, CSS variables, light and dark themes |
| 1.53 | Next.js skeleton | App Router, Tailwind v4, tokens, theme cookie, security headers and CSP |
| ↳ 1.53a | Next.js skeleton | App Router and Tailwind v4; tokens and the theme cookie |
| ↳ 1.53b | Headers and CSP | Security headers and CSP |
| 1.54 | BFF authentication | OIDC with Keycloak, server-side session, `HttpOnly` cookie, CSRF protection |
| ↳ 1.54a | OIDC login and session | OIDC with Keycloak; session on the server, `HttpOnly` cookie |
| ↳ 1.54b | CSRF protection | CSRF protection for the BFF |
| 1.55 | Generated API client | `packages/api-client` from OpenAPI with injectable transport |
| 1.56 | Design system primitives | Button, Input, Badge, Card, Dialog, Drawer on Radix; Storybook; axe checks |
| 1.57 | Data table | TanStack Table + Virtual, URL filters with nuqs |
| 1.58 | App shell | Sidebar, top bar, command palette skeleton (cmdk), four-state screen pattern |
| 1.59 | Charges list | Dense, virtualized list with filters and empty/error/loading states |
| 1.60 | Charge detail timeline | Events, ledger postings and webhook attempts; raw JSON drawer |
| 1.61 | Live updates | The tenant's SSE stream relayed by the BFF, its events written into the TanStack Query cache |
| 1.62 | Charts | visx chart components in the design system (area, bar, sparkline) with accessible tables |
| 1.63 | Dashboard v1 | Balance, inflows over time, recent payments |
| 1.64 | Developer area | API keys (with step-up), webhook endpoints, delivery log and replay |
| ↳ 1.64a | API keys screen | API keys, with step-up |
| ↳ 1.64b | Webhooks screens | Webhook endpoints; delivery log and replay |
| 1.65 | Internationalization | pt-BR and en via `packages/i18n`, error-code messages |

### 4.9 Block I — Data, proof and release

| PR | Title | Delivers |
|---|---|---|
| 1.66 | Synthetic data generator | Seeded months of realistic tenants, charges, refunds and failures |
| 1.67 | Demo mode | MSW fixtures from the generator; static deployment to a free host |
| ↳ 1.67a | MSW fixtures | Fixtures from the generator |
| ↳ 1.67b | Static deployment | Demo mode deployed to a free host |
| 1.68 | End-to-end tests | Playwright journeys (login, charge, live settlement), axe, visual regression |
| ↳ 1.68a | User journeys | Playwright setup; login, charge and live settlement journeys |
| ↳ 1.68b | Accessibility and visuals | axe checks and visual regression |
| 1.69 | DAST | OWASP ZAP baseline against the Compose environment in CI |
| 1.70 | Load tests | k6 scenarios, hot-account comparison, results and charts in the README |
| ↳ 1.70a | k6 scenarios | Load test scenarios |
| ↳ 1.70b | Hot accounts | Hot-account comparison; results and charts in the README |
| 1.71 | Game day 1 | Rails outage and duplicated callbacks at peak; postmortem |
| 1.72 | Game day 2 | Tenant webhook endpoint down for hours; postmortem |
| 1.73 | Phase documentation | Threat model, ASVS mapping, runbooks, `REVIEWERS.md`, README tour |
| ↳ 1.73a | Threat model and ASVS | Phase 1 threat model; controls mapped to OWASP ASVS |
| ↳ 1.73b | Runbooks and reviewer guide | Operational runbooks; `REVIEWERS.md` and the README tour |
| 1.74 | Demo video and release | Recorded demo, GIFs; exit criteria checklist; tag `v0.1.0` |
| ↳ 1.74a | Demo video | Recorded demo and GIFs |
| ↳ 1.74b | Release | Exit criteria checklist; tag `v0.1.0` |

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
