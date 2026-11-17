# 0001. Record architecture decisions

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-17 |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Finaxis is built over months by a single author with AI assistance. Decisions made early (a modular monolith, no card data, the choice of identity provider) shape everything that follows, and their reasons are easily lost: conversations are not kept, AI sessions start without memory, and code shows *what* was built but not *why*.

The project must also be defensible in engineering interviews, which requires the reasoning behind each decision, including the alternatives that were rejected.

## Decision

We will record every non-obvious architecture decision as an Architecture Decision Record (ADR), in the format described by Michael Nygard, extended with a mandatory **Alternatives considered** section.

- ADRs live in `docs/adr/`, are numbered sequentially and are written from [`template.md`](template.md).
- ADRs are proposed and reviewed through pull requests, like code.
- An accepted ADR is immutable; a changed decision is recorded in a new ADR that supersedes it.

## Alternatives considered

### No formal records

Decisions are left in pull request descriptions and conversations. Rejected: the reasoning is scattered, hard to find and impossible to keep current.

### Wiki outside the repository

Rejected: it drifts from the code, is not reviewed in pull requests, and is not versioned with the change it describes.

### A heavier template (e.g. full MADR)

Rejected for now: more fields than a single-author project needs. The alternatives section, the part of MADR that matters most here, is adopted.

## Consequences

### Positive

- The reasoning behind each decision is versioned next to the code and reviewed with it.
- New contributors and AI sessions can recover context without the original conversation.
- Rejected alternatives are documented, so the same discussion is not repeated.

### Negative

- Writing an ADR adds effort to each significant decision. Mitigated by a short template and by recording only non-obvious decisions.
- Superseded ADRs accumulate. This is intended: they preserve the history of the architecture.

## References

- Michael Nygard, "Documenting Architecture Decisions" (2011).
- [Platform overview](../design-docs/2026-11-17-platform-overview.md), sections 13 and 19.6.
