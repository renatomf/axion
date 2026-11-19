# Finaxis

**An open-source, API-first billing and receivables platform built on Brazilian payment rails** (Pix, Pix Automático, boleto), with a double-entry ledger, reconciliation and fraud controls at its core.

Stripe-quality developer experience and Mercury-quality product experience, on Brazilian rails.

> [!IMPORTANT]
> Finaxis is a demonstration project. All money, payment rails and data are simulated. It is not a financial service and holds no real customer data.

## Status

Early development: **Phase 0 (Foundation)**. Nothing is runnable yet.

| Phase | Focus |
|---|---|
| 0 | Foundation: repository, CI with security gates, Spring Boot skeleton |
| 1 | Financial integrity: ledger, tenancy, idempotency, Pix charges, settlement, webhooks, console |
| 2 | Money movement: split, sub-accounts, payout saga, biometric approval |
| 3–5 | Extensions: subscriptions, reconciliation, AI-assisted operations, cloud readiness |

## Planned architecture

| Component | Stack |
|---|---|
| `services/core` | Java 25, Spring Boot, Spring Modulith (modular monolith) |
| `services/rails-simulator` | Spring Boot; simulated SPI/DICT and boleto with failure injection |
| `services/intelligence` | Python, FastAPI |
| `apps/web` | Next.js console and BFF |
| `apps/mobile` | Expo (React Native), Android |
| Infrastructure | PostgreSQL, Keycloak, Docker Compose |

## Documentation

- [Platform overview](docs/design-docs/2026-11-17-platform-overview.md): the approved design.
- [Roadmap](docs/roadmap.md): phases and the pull-request plan.
- [All documentation](docs/README.md)
- [Contributing](CONTRIBUTING.md)

## Getting started

Development happens on Linux (or WSL2 on Windows). Verify your toolchain, then install the repository tooling and git hooks:

```bash
./scripts/check-toolchain.sh
pnpm install
just up    # start PostgreSQL locally (`just up auth` adds Keycloak)
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full setup.

## License

[Apache License 2.0](LICENSE)
