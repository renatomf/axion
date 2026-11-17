# 0002. Build the core as a modular monolith

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-17 |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

`services/core` holds every business rule and is the authority over money. Its most important operation, settlement, must mark a charge as paid, post a balanced ledger entry and record the outgoing event **atomically**: they succeed or fail together.

The project has one author, runs on a 16 GB laptop and must cost nothing to operate. It still needs strong internal boundaries: about fifteen modules (ledger, billing, tenancy, webhooks and others) that must not depend on each other's internals.

The system already has real network boundaries where they add value: the web BFF, the core, the rails simulator and the intelligence service run as separate processes.

## Decision

We will build `services/core` as a **modular monolith**: one deployable Spring Boot application, divided into modules with enforced boundaries.

- Each module exposes only its `api` package; Spring Modulith fails the build on access to another module's internals or on dependency cycles.
- Each module owns one PostgreSQL schema and one database role; no cross-schema queries.
- Modules communicate through their public APIs or through in-process domain events with a transactional outbox.
- A module is extracted into its own service only when it meets explicit criteria: independent scaling needs, fault isolation, an independent release cadence or a dedicated owning team.
- `ledger` and `billing` are never separated, because settlement requires a local transaction.

## Alternatives considered

### Microservices from the start

One service per module. Rejected:

- Settlement would span services and become a saga on the money path, trading an atomic transaction for compensation logic and eventual consistency.
- Operational cost (deployments, networking, observability, local memory) multiplies for a single author.
- Microservices mainly solve team scaling and independent deployment, problems this project does not have.

### Monolith without enforced boundaries

A conventional layered application (controllers, services, repositories). Rejected: nothing prevents modules from reaching into each other's tables and classes, so boundaries erode over time and a later extraction becomes a rewrite.

## Consequences

### Positive

- Settlement stays a single local ACID transaction.
- One build, one deployment and one process to debug and observe.
- Boundaries are verified in CI, so the code stays ready for extraction.
- Extraction is demonstrated once, deliberately: the `webhooks` module in phase 5, which only consumes events and takes no part in money transactions.

### Negative

- The core scales as one unit. Acceptable at the project's load; the extraction criteria define when this changes.
- A defect in one module can affect the whole process. Mitigated by event-only modules never being called synchronously, so they cannot slow down or break settlement.
- Boundary rules depend on tooling (Spring Modulith, ArchUnit) being configured and kept in CI (PR 0.17).

## References

- [Platform overview](../design-docs/2026-11-17-platform-overview.md), sections 3.1, 5.2 and 5.4.
- Spring Modulith reference documentation.
