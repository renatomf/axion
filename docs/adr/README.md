# Architecture Decision Records

One record per non-obvious decision: context, decision, consequences and the alternatives rejected.

## Process

1. Copy [`template.md`](template.md) to `NNNN-short-title.md`, using the next free number.
2. Open a pull request with status `Proposed`. The decision is discussed in review.
3. Set the status to `Accepted` before merging.
4. **Immutability:** an accepted ADR is not rewritten. A changed decision gets a new ADR; the old one is set to `Superseded` and both are linked. Only the status fields of an accepted ADR may change.

Statuses: `Proposed`, `Accepted`, `Superseded`, `Deprecated`.

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-modular-monolith.md) | Build the core as a modular monolith | Accepted |
| [0003](0003-no-card-data.md) | Never handle payment card data | Accepted |
| [0004](0004-jdbc-client-in-the-ledger.md) | Use JdbcClient for ledger persistence | Accepted |
