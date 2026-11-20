# Engineering standards

The standards Finaxis is built to. The goal is code that is **simple, highly modular and deliberate**, not complex. This document expands section 19 of the [platform overview](../design-docs/2026-11-17-platform-overview.md#19-engineering-standards); when they disagree, the design doc wins and the conflict is fixed in a pull request.

A standard that is only written down is a suggestion, so each one states how it is enforced:

- **Build** or **CI**: a tool fails the build or a required check today.
- **Hook**: a local git hook checks it before the commit exists.
- **Review**: code review against the [pull request template](../../.github/pull_request_template.md) checklist.
- **Planned (PR n)**: the roadmap item that introduces the enforcement, usually together with the first code it applies to.

How to run each tool is described in [`CONTRIBUTING.md`](../../CONTRIBUTING.md).

## Business rules (domain)

| Standard | Enforced by |
|---|---|
| Rich domain model: aggregates enforce their own invariants; nothing outside an aggregate can put it into an invalid state | Review |
| Small aggregates referenced by ID; one aggregate changed per transaction, except the settlement transaction (charge and ledger entry) | Review |
| Value objects instead of primitives (`Money`, `Cpf`, `Cnpj`, `PixKey`, `EndToEndId`, `TenantId`), constructed only in a valid state | Review; property tests for `Money` (PR 1.03) |
| Business decisions in named, pure policies (`SplitPolicy`, `RefundPolicy`, `LimitPolicy`, `RetryPolicy`), never in controllers, the BFF or SQL | Review |
| Explicit state machines for charges, payouts and subscriptions; invalid transitions cannot be expressed | Review; tests per machine (PR 1.33) |
| Expected outcomes are values (sealed result types such as `InsufficientFunds`); exceptions are for bugs and infrastructure failures | Review |
| Code names match the glossary (`docs/domain/glossary.md`) | Review |

## Backend (Java / Spring)

| Standard | Enforced by |
|---|---|
| Records for value objects and DTOs; sealed interfaces with pattern-matching `switch`; immutable by default | Review; Error Prone (Build) |
| No `null` in the domain; types are non-null unless annotated `@Nullable` (JSpecify) | NullAway (Build) |
| Constructor injection only | ArchUnit `ArchitectureTest` (Build) |
| No Lombok; records cover the need and keep the code explicit | Review: no Lombok dependency exists; the build check the design calls for is not written yet |
| One module per subpackage of `dev.finaxis.core`; no cycles; a module uses only what another module exposes | Spring Modulith `ModuleStructureTest` (Build) |
| Hexagonal layout per module; `domain` packages free of Spring and persistence types | ArchUnit `ArchitectureTest` (Build) |
| Transactions declared in the application layer only; short; no remote calls inside a transaction | Review; ArchUnit rule with the first transactional code (PR 1.10) |
| Typed, validated `@ConfigurationProperties`; twelve-factor configuration; no secrets in the repository | Review; Gitleaks (Hook, CI) |
| `Clock` injected everywhere; no `now()` without a `Clock` | ArchUnit `ArchitectureTest` (Build) |
| Contract-first OpenAPI; snake_case JSON; RFC 9457 errors; cursor pagination; versioning and deprecation policy | Spectral (CI); Problem Details tests (Build); contract tests: `OpenApiContract` in endpoint tests (Build) |
| Another tenant's resource answers `404`, existing or not, and no list shows it | `TenantIsolationTest` over every operation of the contracts (Build) |
| Structured JSON logs; a correlation ID on every request's log lines; no personal data in logs | Logging tests (Build); Review for personal data |
| One formatter; zero warnings | Spotless, Error Prone, `javac -Werror` (Build); CodeQL (CI) |

The design also lists Semgrep; it is not adopted yet and has no roadmap item.

## Database (PostgreSQL)

| Standard | Enforced by |
|---|---|
| The database is the last line of defense: `NOT NULL`, `CHECK`, `UNIQUE` and foreign keys express every rule that can be expressed declaratively | Review; constraint tests from the ledger schema (PR 1.09) |
| Money as `BIGINT` minor units; timestamps as `timestamptz`; status as `text` with `CHECK`; UUIDv7 primary keys | Review |
| snake_case names; one schema per module; `_id` and `_at` suffixes; every foreign key indexed | Review |
| Flyway migrations, versioned and forward-only; an applied migration is never edited; breaking changes follow expand/contract | Flyway: unparseable names fail the build, edited migrations fail checksum validation at startup (Build); Review for expand/contract |
| Least-privilege role per module; Row-Level Security for tenant data; the application never connects as owner or superuser | Role privilege tests per module (PRs 1.09, 1.22c); policy tests per table (PRs 1.22b, 1.22h); login role tests (PR 1.22d) |
| Keyset (cursor) pagination, never `OFFSET` on large tables; no N+1 queries; `EXPLAIN` reviewed for critical queries; every index justified | Review; query tests from the ledger queries (PR 1.14) |

## Frontend (Next.js / TypeScript)

All frontend standards arrive with the console (block H, PRs 1.50–1.65):

| Standard | Enforced by |
|---|---|
| Feature-based modules (`features/charges`, `features/ledger`…); features never import each other's internals; shared code lives in `packages/` | Planned: ESLint boundary rules (PR 1.50) |
| Server Components by default; Client Components only where interaction requires them | Planned: Review |
| One home per kind of state: server state in TanStack Query, shareable UI state in the URL (nuqs), local state in components | Planned: Review |
| TypeScript `strict` and `noUncheckedIndexedAccess`; no `any`; Zod at every boundary; a generated API client | Planned: compiler and ESLint (PR 1.50); generated client (PR 1.55) |
| Integer minor units end to end; formatting only at the edge with `Intl.NumberFormat` | Planned: Review |
| Every screen designs loading, empty, error and success states | Planned: Review; app shell pattern (PR 1.58) |
| Design system as tokens → primitives → composites, documented in Storybook; WCAG 2.2 AA | Planned: axe checks (PRs 1.56, 1.68) |
| ESLint and Prettier with zero warnings | Planned (PR 1.50) |

## Mobile and Python

| Standard | Enforced by |
|---|---|
| Mobile: the same feature-based structure; explicit poor-connectivity handling with idempotent retries; secure storage only for tokens and keys | Planned (phase 1b) |
| Python: hexagonal layering; Ruff, mypy strict, Pydantic at boundaries, pytest; dependencies managed by uv | Planned (phase 4) |

## Cross-cutting

| Standard | Enforced by |
|---|---|
| One command per task from the root `justfile` | Review |
| EditorConfig for every file | editorconfig-checker (Hook) |
| No secrets in the repository | Gitleaks (Hook, CI) |
| Conventional Commits | commitlint (Hook) |
| Supply chain: dependencies and actions pinned, updated by Dependabot; container image scanned | Dependabot; Trivy (CI) |
| Every non-obvious decision has an [ADR](../adr/), including rejected alternatives | Review |
| Events and APIs are versioned with an explicit deprecation policy | Review; event catalog (PR 1.43) |
| Feature flags (database-backed, audited) for kill switches and gradual rollout | Not scheduled yet |
| Every pull request answers the correctness, tests, security, observability and documentation checklist | Pull request template (Review) |

## Comments and documentation

Code says what it does; a comment says only what the code cannot. A reader is assumed to know Java, Spring and PostgreSQL.

| Standard | Enforced by |
|---|---|
| Javadoc on a module's public API, the types in its root package and in `shared`: one or two sentences on what it guarantees, with the design doc section it implements | Review |
| No Javadoc inside a module (`application`, `domain`, `persistence`, `web`, `security`) unless the contract is not obvious from the name and the types | Review |
| A comment explains *why*, never *what*: a security rule, a concurrency guard, an invariant, a non-obvious query. Two or three lines at most; a longer reason belongs in the design doc, referenced by section | Review |
| No comment explains the language or a framework (what `@Valid`, `JdbcClient` or `OncePerRequestFilter` does) | Review |
| A test's name states the behavior it proves; a comment only where the setup is not obvious | Review |
| An abstraction (interface, enum, wrapper) needs more than one use or a module boundary; otherwise the code stays direct | Review |
| Documents are as short as their reader allows: design docs and ADRs record decisions and their alternatives; `CONTRIBUTING.md` holds commands and rules, not tutorials | Review |

## Changing a standard

A standard changes in a pull request that updates this document and, when the change is significant, the overview design and an ADR. Prefer adding a check that enforces it over adding a sentence.
