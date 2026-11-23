# 0005. Use Keycloak for human identity

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-19 |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

People reach the core through the console and, in phase 1b, the mobile app. Their identity provider must offer:

- OpenID Connect with the authorization code flow and PKCE, for the BFF and for a native app (RFC 8252).
- Mandatory MFA with TOTP and passkeys (WebAuthn), and brute-force protection.
- Step-up: an `acr` claim and `auth_time`, and re-authentication on demand with `max_age` (RFC 9470), for API keys, webhook endpoints and payout approvals.
- OAuth 2.0 Token Exchange (RFC 8693) in phase 4, to give the intelligence service a down-scoped, short-lived, read-only token derived from the user's.
- Operation without internet access: in CI, in Testcontainers and on a laptop running the demo.
- Configuration kept as code in the repository, reviewed like any other change.

The platform overview chose Keycloak and deferred the record of that decision to an ADR (section 4.3). The [tenancy and access design](../design-docs/2026-11-19-tenancy-and-access.md) relies on it.

## Decision

We will use Keycloak as the identity provider for people. It authenticates them; the core authorizes them, with roles stored in the `tenancy` module (design, section 4.1).

- One realm, `axion`, defined as a JSON export in `infra/keycloak/` and imported at startup. It holds no secrets: client secrets come from the environment.
- Keycloak runs in Docker Compose with its own database and database role on the project's PostgreSQL server, never in a module's schema.
- Integration tests start the same realm in a Keycloak Testcontainer.
- The version is the latest stable release when PR 1.18 adds it, upgraded through Dependabot like every other image.

## Alternatives considered

### A hosted provider (Clerk, Auth0, Amazon Cognito)

Least to operate, with polished login screens. Rejected:

- CI, tests and the offline demo would depend on an external service and its account.
- Token exchange and fine-grained step-up are missing or tied to paid plans.
- Identity data would live outside the project's control, against the data-control goal of the overview.

### Spring Authorization Server

Spring's own authorization server, in the same stack as the core. Rejected: it is a framework for building an identity provider, not one. Login screens, MFA, passkeys, brute-force protection, account recovery and an admin console would all be code to write and secure, which is identity engineering outside this project's scope.

### Other self-hosted providers (ZITADEL, Authentik, Ory)

Each is credible, and some are lighter than Keycloak. Rejected because Keycloak combines every requirement above in one product, with first-class Testcontainers and Spring Security support and the widest documentation, which matters for an author new to the stack.

## Consequences

### Positive

- Every requirement in the context is configuration, not code.
- The whole system, identity included, runs offline from one `just up`.
- The realm is versioned and reviewed in pull requests.

### Negative

- Keycloak needs about 1 GB of memory. Mitigated by its own Compose profile, started only when the console or authentication tests need it.
- Its realm export is verbose and its upgrades occasionally change behavior. Mitigated by keeping the realm minimal and testing token validation against the real image.
- Login screens use Keycloak's theme. Accepted for phase 1; a custom theme can follow the design system.

## References

- [Tenancy and access design](../design-docs/2026-11-19-tenancy-and-access.md), sections 4.1 and 4.2.
- [Platform overview](../design-docs/2026-11-17-platform-overview.md), sections 3.3 and 4.3.
- RFC 8252 (OAuth 2.0 for Native Apps), RFC 8693 (OAuth 2.0 Token Exchange), RFC 9470 (OAuth 2.0 Step Up Authentication Challenge).
