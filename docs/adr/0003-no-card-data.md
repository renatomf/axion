# 0003. Never handle payment card data

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-17 |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Finaxis is a billing and receivables platform on Brazilian payment rails. Its value lies in Pix, Pix Automático and boleto, plus the ledger, reconciliation and fraud controls around them.

Any system that stores, processes or transmits payment card data falls within the scope of PCI DSS. Meeting it requires controls such as network segmentation, key management, strict access control, audit logging and regular assessments, applied to every component that touches card data or can affect its security.

Reducing the amount of sensitive data a system handles is itself a security control: data that is never collected cannot leak.

## Decision

We will **not process, store or transmit payment card data** anywhere in the platform: no card numbers (PAN), security codes, track data or PINs, in any service, database, log, test fixture or demo.

- Supported payment methods are Pix, Pix Automático and boleto only.
- Card processing is listed as a non-goal in the platform overview.
- If cards are ever needed, they will be introduced through a new ADR, using a third-party provider so that card data never reaches Finaxis systems.

## Alternatives considered

### Card processing through a third-party tokenization provider

Card data is captured by the provider (e.g. hosted fields) and Finaxis only sees tokens. Rejected for now: it adds an external dependency and cost, and parts of the platform could still fall within PCI DSS scope, while cards add little to what the project sets out to demonstrate.

### Direct card processing

Rejected: full PCI DSS scope across the platform, a large compliance effort unrelated to the project's goals, and significant risk for no demonstrable benefit.

## Consequences

### Positive

- PCI DSS is out of scope, keeping the security effort on the controls that matter for the chosen rails (tenant isolation, ledger integrity, webhook signing, fraud).
- No card data can leak, because none exists.
- The architecture stays focused on Brazilian rails.

### Negative

- The platform cannot serve use cases that require cards. Accepted: they are outside the project's scope.
- The decision must be actively protected: a field that accepts free text could still receive a card number. Mitigated by explicit DTOs (no unvalidated free-form payloads), no personal or payment data in logs, and synthetic data only.

## References

- [Platform overview](../design-docs/2026-11-17-platform-overview.md), sections 1.2 and 4.1.
- PCI Security Standards Council, PCI DSS.
