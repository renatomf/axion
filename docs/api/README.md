# API

Reference material for API consumers.

- **OpenAPI reference** for the public `/v1` API. The contract, [`services/core/openapi/v1.yaml`](../../services/core/openapi/v1.yaml), is the source of truth; these pages explain it as endpoints arrive. How contracts are written and linted: [`CONTRIBUTING.md`, APIs](../../CONTRIBUTING.md#apis).
- **[Webhook guide](webhooks.md):** event types and payloads, verifying signatures, answering, rolling the secret and receiving on a developer's machine.
- **[Idempotency guide](idempotency.md):** how to use `Idempotency-Key` and how conflicts are reported.
