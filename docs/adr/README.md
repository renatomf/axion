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
