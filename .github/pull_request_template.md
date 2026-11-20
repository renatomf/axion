## Context

<!-- Why this change exists. Link the roadmap item or design doc. -->

Closes #

## Changes

<!-- What changed, one bullet per file or component. -->

-

## Rationale

<!-- Why this approach; alternatives considered and rejected. -->

## How to test

```bash

```

## Security checklist

- [ ] No secrets, credentials or real personal data added (all data is synthetic)
- [ ] Inputs are validated at the boundary; no mass assignment
- [ ] Authentication and authorization apply to every new endpoint (deny by default)
- [ ] Tenant is derived from the credential, never from the request; cross-tenant access returns `404`
- [ ] Errors and logs leak no internals, secrets or personal data
- [ ] New dependencies are justified and come from trusted sources
- [ ] Abuse cases are tested where the change touches money, identity or tenant data

<!-- Mark items that do not apply as N/A instead of deleting them. -->

## Review checklist

- [ ] **Correctness:** invariants hold; edge cases and failure paths handled
- [ ] **Tests:** prove the behavior, not just coverage
- [ ] **Observability:** relevant logs and metrics, with correlation IDs
- [ ] **Documentation:** affected docs, ADRs and API reference updated
- [ ] **Size:** reviewable with care; a split names its reason (see `CONTRIBUTING.md`)

## Review questions

<!-- Questions the author answers before merging. -->

1.
