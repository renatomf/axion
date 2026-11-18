# 0004. Use JdbcClient for ledger persistence

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-18 |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Most modules of the core will persist aggregates with Spring Data JPA (overview, section 5.3). The ledger is different. Its correctness depends on the exact SQL it runs:

- Balances change only through a conditional `UPDATE ... WHERE balance + :delta >= 0`, whose row count is the overdraft decision.
- Balance rows are locked in ascending account ID order, to prevent deadlocks.
- A source key is claimed with `INSERT ... ON CONFLICT DO NOTHING RETURNING id`.
- Entries and postings are append-only: the service role has no `UPDATE` or `DELETE` on them.
- Postings are inserted in batches, and history is read with keyset pagination.

Every statement must be visible in the code, so that a reviewer can check its locks and its effect on the invariants.

## Decision

We will write the ledger's persistence with Spring's `JdbcClient`: explicit SQL with named parameters, in the module's outbound adapter, with rows mapped to domain types by hand. The domain stays plain Java.

`JdbcClient` comes with `spring-boot-starter-jdbc`, which the service already uses; no dependency is added. Transactions are still declared with Spring's `@Transactional` in the application layer.

## Alternatives considered

### Spring Data JPA (Hibernate)

Rejected for the ledger:

- Hibernate decides when and in which order SQL runs (flush on commit, dirty checking). The lock order and the single conditional update would have to be forced around it with native queries, leaving two persistence styles in one module.
- Dirty checking issues an `UPDATE` when a loaded entity changes. On append-only tables that is either a bug waiting to happen or a permission error at commit.
- Its features (lazy loading, caching, cascades) solve problems the ledger does not have: rows are written once and read in simple queries.

### Spring Data JDBC

Simpler than JPA and closer to SQL, but aggregate-oriented: saving an aggregate rewrites its child rows, which an append-only table forbids, and the conditional update and `ON CONFLICT` would still be custom queries.

### jOOQ

Type-safe SQL checked at compile time, the strongest alternative. Rejected for now: it needs code generation from a live schema in the build, a new dependency and a DSL to learn, for about a dozen statements. It remains the upgrade path if the ledger's SQL grows.

## Consequences

### Positive

- Every lock, condition and constraint the invariants rely on is in one readable SQL statement in the code.
- No hidden writes: the service runs exactly the statements written.
- No new dependency or build step.

### Negative

- Mapping rows to types is written by hand. Mitigated by the ledger's small number of tables and queries.
- SQL errors, such as a misspelled column, appear only when the statement runs. Mitigated by integration tests against a real PostgreSQL (Testcontainers) for every statement.
- The ledger persists differently from other modules. Accepted: the difference is confined to one module's adapter, and its reason is recorded here.

## References

- [Ledger design](../design-docs/2026-11-18-ledger.md), sections 3.3 and 5.
- [Platform overview](../design-docs/2026-11-17-platform-overview.md), sections 5.3 and 6.
- Spring Framework reference: `JdbcClient`.
