# Tenancy and access — Design

| | |
|---|---|
| **Status** | Draft |
| **Date** | 2026-11-19 |
| **Scope** | The `tenancy` and `access` modules of the core: tenants, environments, members and roles, human and machine authentication, tenant context, Row-Level Security and rate limiting. Roadmap PRs 1.17–1.24. |
| **Parent** | [Platform overview](2026-11-17-platform-overview.md), sections 4.3, 4.6 and 4.7 |

---

## 1. Problem

Many businesses share one deployment of the platform. Every request must act for exactly one of them, and nothing one business does may read or change another's data, even when the code has a bug. Two kinds of caller reach the core: people, through the console, and the businesses' own systems, through the public API. Both need credentials that can be revoked, permissions that match their job, and limits that keep one caller from exhausting the platform.

The platform overview fixes the controls: Keycloak with mandatory MFA for people, hashed and scoped API keys for machines, RBAC, and four layers of tenant isolation ending in PostgreSQL Row-Level Security. This document decides how the `tenancy` and `access` modules implement them.

## 2. Goals and non-goals

**Goals**

- Every request is bound to one tenant, derived from its credential; a resource of another tenant is indistinguishable from one that does not exist.
- Test and live data never mix, including money in the ledger.
- A leaked API key is useless once revoked, recognizable by secret scanners, and never recoverable from the database.
- Every operation declares the permission it needs; an operation that declares none is denied.
- Tenant isolation holds even if an application query forgets its tenant filter.

**Non-goals**

- Self-service sign-up, onboarding and KYC. Phase 1 provisions organizations and their first admin with a seed.
- Invitations and member management screens. Members are provisioned in phase 1; invitations get their own design in phase 2, with maker-checker (section 12).
- Platform staff and back-office roles.
- Federation with a business's own identity provider (SAML, enterprise SSO).
- Maker-checker and device-bound approvals (phase 2), and delegated tokens for the intelligence service (phase 4). The model leaves room for both.

## 3. Model

### 3.1 Concepts

| Concept | Meaning |
|---|---|
| **Organization** | A business that uses the platform. Its members and their roles belong to it |
| **Environment** | `test` or `live`. Test moves simulated money for integration work; live is the "real" environment of the demonstration |
| **Tenant** | One environment of one organization: the unit of data isolation. Every organization has exactly two tenants, one per environment. Every tenant-owned row carries a `tenant_id` |
| **Member** | A person, identified by Keycloak, who belongs to an organization with one or more roles |
| **Role** | A named set of permissions given to a member: `admin`, `finance`, `approver`, `developer`, `read_only` |
| **Permission** | The right to perform one kind of operation, such as `charges:write`. Code checks permissions, never roles |
| **API key** | A secret credential that a tenant's system uses on `/v1`. It belongs to one tenant, so to one environment, and carries scopes |
| **Scope** | A permission granted to an API key |
| **Platform tenant** | The tenant that owns the platform's own ledger accounts, such as `platform:settlement`. There is one per environment |

### 3.2 Environments are tenants

The overview gives each tenant two environments. This design makes each environment a tenant of its own, grouped under an organization: `tenant_id` already names the isolation unit in the ledger, and keeping one isolation key means one filter per query, one Row-Level Security policy per table and one dimension for the isolation tests.

The consequences are deliberate:

- A test API key reaches the test tenant only, through the same mechanism that keeps two businesses apart. There is no `livemode` flag to forget in a query.
- Test money never touches live accounts. Each environment has its own platform tenant, so a test settlement posts to the test `platform:settlement`, never the live one.
- Limits, webhook endpoints and API keys are per environment, as tenants' integrations expect.
- Members and roles belong to the organization and apply to both of its tenants.

### 3.3 Roles and permissions

Each role is a fixed set of permissions, defined in code and tested; the permissions of a member are the union of their roles' permissions.

| Permission | admin | finance | approver | developer | read_only | API key scope |
|---|---|---|---|---|---|---|
| `charges:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `charges:write` | ✓ | ✓ | | ✓ | | ✓ |
| `refunds:write` | ✓ | ✓ | | | | ✓ |
| `balances:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `webhooks:manage` | ✓ | | | ✓ | | ✓ |
| `api_keys:manage` | ✓ | | | ✓ | | |
| `members:manage` | ✓ | | | | | |
| `payouts:approve` (phase 2) | | | ✓ | | | |

- **Human-only permissions** (`api_keys:manage`, `members:manage`, `payouts:approve`) are never API key scopes: a leaked key cannot mint other keys, add members or approve payouts.
- **A key's scopes are a subset of its creator's permissions**, so a developer cannot issue a key that refunds; only an admin can.
- **An admin does not approve payouts**: approving is a separate role, the first step of maker-checker in phase 2.
- **An organization always has an admin**: removing or demoting the last one is rejected.

### 3.4 Schema

The `tenancy` and `access` schemas follow the conventions of the ledger (design, section 3.3): UUIDv7 keys, `timestamptz`, enumerations as `text` with a `CHECK`.

```
tenancy.organizations
  id            uuid primary key
  name          text not null                        -- display name, at most 100 characters
  is_platform   boolean not null                     -- the platform's own organization
  created_at    timestamptz not null
  unique index on (is_platform) where is_platform    -- at most one

tenancy.tenants
  id               uuid primary key
  organization_id  uuid not null references organizations
  environment      text not null                     -- TEST | LIVE
  status           text not null                     -- ACTIVE | SUSPENDED | CLOSED
  created_at       timestamptz not null
  unique (organization_id, environment)

tenancy.members
  id               uuid primary key
  organization_id  uuid not null references organizations
  user_subject     text not null                     -- the Keycloak user's `sub`
  created_at       timestamptz not null
  unique (organization_id, user_subject)
  index (user_subject)

tenancy.member_roles
  member_id  uuid not null references members
  role       text not null                           -- ADMIN | FINANCE | APPROVER | DEVELOPER | READ_ONLY
  primary key (member_id, role)

access.api_keys
  id            uuid primary key
  tenant_id     uuid not null
  name          text not null                        -- a label chosen by the creator
  secret_hash   bytea not null unique                -- SHA-256 of the whole key, section 4.3
  last_four     char(4) not null                     -- shown as sk_live_…a1b2
  scopes        text[] not null                      -- checked against the scope list
  created_by    uuid not null                        -- the member who issued it
  created_at    timestamptz not null
  expires_at    timestamptz null                     -- set when the key is rolled
  revoked_at    timestamptz null
  last_used_at  timestamptz null
  index (tenant_id)
```

- An organization is created with both of its tenants, in one transaction.
- The **platform organization** and its two tenants are created by a migration with fixed IDs. They have no members and no API keys.
- A member is linked to Keycloak only by `user_subject`, a stable identifier that is not personal data. Names and emails stay in Keycloak; the core reads them from the token when it needs to display them.
- Legal and Pix receiver details (CNPJ, city) arrive with billing (block F), the first feature that needs them.

### 3.5 Refinements of the overview

| Overview | This design | Reason |
|---|---|---|
| A tenant has two environments (section 5.1) | An organization has two tenants, one per environment | One isolation key; test money never reaches live accounts (section 3.2) |
| "API keys per tenant and environment" (section 4.3) | API keys per tenant | Same meaning: a tenant is one environment |
| A reserved platform tenant (ledger design, section 3.3) | One platform tenant per environment | Test and live platform accounts stay apart |

## 4. Authentication

`/v1` accepts API keys only and `/internal` accepts Keycloak access tokens only; a credential of the wrong kind is rejected as invalid. Both arrive in the `Authorization: Bearer` header and nowhere else: a credential in a query string or a body is ignored, so it never reaches proxy logs or browser history.

### 4.1 Members

Keycloak authenticates people ([ADR 0005](../adr/0005-keycloak-for-human-identity.md)); the core authorizes them. The console's BFF runs the OpenID Connect login, keeps the tokens server-side and calls `/internal` with the member's access token (overview, section 3.3).

**Keycloak realm (PR 1.18).** One realm, `finaxis`, kept as code in `infra/keycloak/` and imported at startup, with no secrets in it:

| Setting | Value |
|---|---|
| MFA | Required for every user: TOTP or a passkey (WebAuthn), enrolled at first login |
| Passwords | At least 12 characters, not the username; brute-force detection locks an account after repeated failures |
| Access token | 5 minutes |
| Session | 30 minutes idle, 10 hours maximum |
| Clients | `finaxis-console` (the BFF, confidential, authorization code with PKCE); the core is an audience, not a client that logs users in |

**Token validation (PR 1.19).** The core is an OAuth 2.0 resource server. It accepts an access token only if all of these hold:

- the signature verifies against the realm's published keys (JWKS), with an allowed algorithm (`RS256`); `none` and symmetric algorithms are rejected;
- `iss` is the realm, `aud` contains `finaxis-core` and `azp` is an allowed client;
- `exp` and `nbf` hold, with 30 seconds of clock skew.

The core reads only `sub`, `acr` and `auth_time` from the token. Roles are not in the token: they are looked up in `tenancy` on every request, so removing a role takes effect on the next request instead of when the token expires.

### 4.2 Step-up

Some operations need proof that the member authenticated recently, not just a valid session: issuing, rolling and revoking API keys, changing webhook endpoints, managing members and, in phase 2, approving payouts.

Such an operation requires an `auth_time` at most 5 minutes old and an `acr` that shows MFA. Otherwise the core answers `403` with code `step_up_required` and the challenge of RFC 9470, which the BFF answers by sending the member back to Keycloak to authenticate again:

```
WWW-Authenticate: Bearer error="insufficient_user_authentication",
  error_description="A recent authentication is required", max_age=300
```

### 4.3 API keys

**Format.** `sk_live_` or `sk_test_`, then 30 random characters and a 6-character checksum, all base62:

```
sk_<environment>_<30 random characters><6 checksum characters>
```

No real-looking key appears in this repository, so that the secret scanners stay strict.

- The 30 random characters come from `SecureRandom`: about 178 bits, beyond any brute force.
- The checksum is the CRC32 of the random part, as in GitHub's token format. It lets the core reject a mistyped or invented key without a database query, and lets secret scanners recognize a real key with few false positives. Gitleaks gets a rule for the format.
- The environment in the prefix tells a reader which environment a leaked key reaches; the core never trusts it, and reads the tenant from the stored key.

**Storage.** The key is shown once, when it is issued, and never stored. The database keeps its SHA-256 hash, unique and indexed, so authentication is one indexed lookup. A fast hash is right here: slow hashes such as Argon2 protect low-entropy passwords from guessing, and a 178-bit random key cannot be guessed, while a slow hash would add tens of milliseconds to every request. Lists show the key's name, `last_four` and dates.

**Lifecycle (PR 1.20).**

| Operation | Effect | Needs |
|---|---|---|
| Issue | New key with a name and scopes, a subset of the issuer's permissions (section 3.3). At most 10 unrevoked keys per tenant | `api_keys:manage`, step-up |
| Roll | Issues a replacement with the same name and scopes, and sets the old key's `expires_at` after a grace period, from zero to 7 days (default 24 hours), so the tenant can deploy the new key first | `api_keys:manage`, step-up |
| Revoke | Sets `revoked_at`; the key stops working on the next request | `api_keys:manage`, step-up |

A key is never updated otherwise and never deleted: its row is the record of what it could do and who issued it. `last_used_at` is updated at most once an hour per key, with a conditional update, so busy keys add no write per request.

### 4.4 Authentication failures

| Case | Response | Code |
|---|---|---|
| No credential, malformed, wrong kind, bad checksum, unknown, expired or revoked key, invalid token | `401` | `invalid_credentials` |
| Valid credential of a tenant that is not `ACTIVE` | `403` | `tenant_inactive` |
| Missing permission or scope | `403` | `insufficient_permission` |
| Recent authentication required | `403` | `step_up_required` |
| Resource of another tenant, or a tenant the member does not belong to | `404` | `not_found` |

Every `401` looks the same, so a caller cannot learn whether a key exists, expired or was revoked; logs and the metric `finaxis_authentication_failures_total{reason}` record the reason, with the key's ID when there is one and never the secret.

## 5. Authorization and tenant context

### 5.1 Principals

Authentication ends with one of two principals, a sealed type in `access`:

```java
sealed interface Caller permits ApiKeyCaller, MemberCaller {
    TenantId tenant();
    Set<Permission> permissions();
}
record ApiKeyCaller(TenantId tenant, ApiKeyId key, Set<Permission> permissions) implements Caller {}
record MemberCaller(TenantId tenant, MemberId member, Set<Permission> permissions, Instant authTime) implements Caller {}
```

**API keys.** The tenant is the key's tenant and the permissions are its scopes. Nothing in the request can change either.

**Members.** A person may belong to several organizations, so the request names the tenant it acts on, in the path: tenant-scoped internal routes live under `/internal/tenants/{tenant_id}/…`. The core resolves that tenant only through the member's own memberships: one indexed query finds the tenant, checks that it is `ACTIVE` and that the user is a member of its organization, and loads their roles. A tenant the user does not belong to answers `404`, exactly like a tenant that does not exist. The path selects among the caller's tenants; it never grants access to one. `/internal/me` lists the caller's organizations and tenants, for the console's environment switcher.

A request body never carries a tenant ID. Request DTOs have no such field, so it cannot be mass-assigned.

### 5.2 Permissions per operation

Every controller method declares exactly one permission, with `@RequiresPermission(CHARGES_WRITE)`, and an interceptor checks it against the caller before the method runs. A handler without the annotation is denied at runtime, and an architecture test fails the build if one exists, so a new endpoint cannot be open by omission. This extends the deny-by-default rule the security filter chain applies to paths (PR 0.19) down to each operation.

### 5.3 Tenant context

The tenant of the request reaches the database through two independent paths, so that a mistake in one is caught by the other:

1. **Explicitly, in every query.** The controller passes the caller's `TenantId` to the application service, and every query filters by it (overview, section 4.3, layer ②).
2. **Implicitly, to Row-Level Security.** After authentication, a filter runs the rest of the request inside a Java `ScopedValue` holding the tenant (`TenantContext`, in `shared`). At the start of every transaction, a Spring `TransactionExecutionListener` reads it and binds it to the transaction with `set_config('finaxis.tenant_id', …, true)`. The `true` makes the setting transaction-local: it disappears at commit or rollback, so a pooled connection never carries one request's tenant into the next.

A `ScopedValue` (final in Java 25) is bound for the duration of a call and cannot be changed or leak afterwards, unlike a `ThreadLocal`, which must be cleared by hand. Work outside a request, such as an event listener, binds the tenant from the event with `TenantContext.run(tenant, …)`; work with no tenant binds none.

**Every database access runs in a transaction**, reads included (`@Transactional(readOnly = true)`). Outside a transaction no tenant is bound and no module role is set (section 5.6), so the access fails instead of running unscoped.

### 5.4 Row-Level Security

Every table holding tenant data enables Row-Level Security (PR 1.22), with policies of this shape:

```sql
ALTER TABLE access.api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.api_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON access.api_keys
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());
```

- `shared.current_tenant()` returns `nullif(current_setting('finaxis.tenant_id', true), '')::uuid`. After a transaction-local setting ends, PostgreSQL returns an empty string rather than null, and casting it would fail; `nullif` turns it into null.
- With no tenant bound, the function returns null, `tenant_id = null` is never true, and the policy shows no rows and accepts no inserts: Row-Level Security fails closed.
- `FORCE` applies the policies to the table owner too. The service never connects as the owner, but a migration or a manual session that does is not exempt by accident.
- `USING` filters what a statement can read, update or delete; `WITH CHECK` rejects a row written for another tenant.

The `tenancy` tables are the directory that defines tenants, read by authentication before any tenant is known; they are reached only through `TenancyApi` and have no tenant policy. Authentication looks up an API key by its hash before the tenant is known, through one `SECURITY DEFINER` function, `access.find_api_key(hash)`, that returns the single key with that hash and nothing else: it cannot list keys, only resolve a secret the caller already holds.

**Jobs that span tenants**, such as the ledger verifier and hot-account folding, connect through their own role (`finaxis_ledger_jobs`) with read-only policies written for that role (`FOR SELECT TO finaxis_ledger_jobs USING (true)`). No role has `BYPASSRLS`, which would exempt it from every policy on every table.

### 5.5 Row-Level Security in the ledger

A settlement runs in the context of the tenant who is paid, but posts to the platform's accounts too (ledger design, section 3.3). So the ledger's policies admit the platform tenant of the same environment, which the binding listener sets as `finaxis.platform_tenant_id` beside the tenant:

| Table | A tenant reads | A tenant writes |
|---|---|---|
| `accounts` | Its own accounts and the platform's | Its own accounts |
| `entries` | Its own entries | Its own entries |
| `postings` | Postings to its own accounts, and the platform legs of its own entries | Postings of its own entries, to its own or the platform's accounts |
| `balances` | Balances of its own accounts | Balances of its own accounts |

A tenant therefore sees the fee leg of its own settlement, which tells it nothing about anyone else, and never another tenant's postings to the platform. This refines the ledger design, which hid platform postings entirely: the posting algorithm reloads every leg of an existing entry to check idempotency (section 5.1, step 2), and must see them all.

### 5.6 Database roles

The service logs in as `finaxis_app`, a role with `NOINHERIT` and no privileges of its own. It is a member of every module role (`finaxis_ledger`, `finaxis_tenancy`, `finaxis_access`, …), and each module's API entry point switches the transaction to its module's role with `SET LOCAL ROLE`, restoring the caller's role when it returns. A module's statements thus run with only its own privileges, even inside another module's transaction, as settlement requires.

This guards against mistakes, such as a query reaching into another module's schema; it does not stop an attacker who can run arbitrary SQL in the service's session, who could switch roles too. Against tenant data, the barriers are the tenant derived from the credential and Row-Level Security; against SQL injection, parameterized statements only, checked by Semgrep.

## 6. Rate limiting

Limits protect the platform from one caller's bug or abuse, not tenants from each other's business volume (amount and velocity limits belong to `risk`, PR 1.37). They apply after authentication, so they count per credential (PR 1.24):

| Bucket | Live | Test |
|---|---|---|
| Per tenant | 100 requests/s, bursts of 200 | 25 requests/s, bursts of 50 |
| Per API key | 50 requests/s, bursts of 100 | 25 requests/s, bursts of 50 |
| Per member | 20 requests/s, bursts of 40 | 20 requests/s, bursts of 40 |

- Each bucket is a token bucket (Bucket4j). A request takes one token from each bucket that applies and is rejected if any is empty.
- A rejected request gets `429` with code `rate_limited`, a `Retry-After` header and the `RateLimit-Policy` and `RateLimit` headers of the IETF draft, so a client can slow down before it is rejected.
- Buckets live in memory, in a bounded cache. Phase 1 runs one core instance, so a limit is exact. With several instances each enforces its own copy, so the effective limit multiplies; a shared store (PostgreSQL or Redis) is the documented path when the core scales out (phase 5).
- Unauthenticated traffic never reaches the buckets: the reverse proxy limits it per IP address (overview, section 4.2), and the BFF limits each session (section 4.4).

The tenant values match the defaults that Stripe publishes, which integrators already expect. They are a starting point, revised with the load tests (PR 1.70).

## 7. API

Other modules use the two modules only through their APIs, in each module's own package:

```java
public interface TenancyApi {
    Optional<TenantView> tenant(TenantId tenant);
    Optional<Membership> membership(String userSubject, TenantId tenant);   // roles, if a member
    List<Membership> memberships(String userSubject);
    TenantId platformTenant(Environment environment);
}

public interface AccessApi {
    IssuedApiKey issue(TenantId tenant, String name, Set<Permission> scopes, MemberId issuer);
    IssuedApiKey roll(ApiKeyId key, Duration grace, MemberId issuer);
    void revoke(ApiKeyId key, MemberId revoker);
    List<ApiKeyView> keys(TenantId tenant);
}
```

- `TenantId` and `Environment` live in `shared`, with `TenantContext`: every module needs them, and the ledger already stores tenant IDs.
- `IssuedApiKey` is the only type that holds a secret, returned once by `issue` and `roll`; its `toString` masks it. `ApiKeyView` has the name, `last_four`, scopes and dates.
- Public IDs: `org_` for organizations, `ten_` for tenants, `mem_` for members and `key_` for API keys.
- Endpoints, contract-first in `internal.yaml`: `GET /internal/me`; `GET` and `POST /internal/tenants/{tenant_id}/api_keys`; `POST …/api_keys/{key_id}/roll` and `POST …/api_keys/{key_id}/revoke`.
- Issuing, rolling and revoking a key, and changing a member's roles, publish events (`ApiKeyIssued`, `ApiKeyRevoked`, `MemberRolesChanged`) for the audit trail, once the event registry exists (block G).

## 8. Testing

| Suite | What it proves | PR |
|---|---|---|
| Domain unit | The role matrix of section 3.3; a key's scopes stay within its issuer's permissions and the machine scopes; the last admin cannot be removed | 1.17, 1.20 |
| Property-based (jqwik) | Any issued key passes its checksum; changing any one character fails it | 1.20 |
| Schema (Testcontainers) | Constraints of section 3.4; an organization always has two tenants | 1.17 |
| Token validation (Keycloak in Testcontainers) | A realm token is accepted; a wrong issuer, audience or algorithm, or an expired token, is rejected; an old `auth_time` yields the step-up challenge | 1.19 |
| API key authentication | Unknown, revoked, expired and mistyped keys get the same `401`; a key works only for its own tenant | 1.21 |
| Row-Level Security | As each module role: with no tenant bound, no rows are read and inserts fail; with tenant A bound, B's rows are invisible; the setting does not survive into the next transaction on the same connection | 1.22 |
| Tenant isolation harness | For every operation in the OpenAPI contracts, tenant A's credential on tenant B's resources gets `404`, and lists never include them; a test key never reaches live data | 1.23 |
| Rate limiting | A burst beyond the bucket gets `429` with `Retry-After`; buckets of different keys are independent | 1.24 |

The isolation harness reads the operations from the contracts, so a new endpoint is covered without anyone remembering to add it; an operation it cannot exercise fails the suite until it gets a fixture.

## 9. Trade-offs and alternatives

| Alternative | Why it was rejected |
|---|---|
| **Environment as a column** beside `tenant_id` (a `livemode` flag on every row) | Two filters in every query and policy, and forgetting the second mixes test and live data, money included. The platform accounts would need an environment anyway |
| **A database or schema per tenant** | Migrations, connection pools and cross-tenant jobs multiply with tenants. Row-Level Security on shared tables scales to thousands of tenants with one schema |
| **Roles in Keycloak** (groups or role claims in the token) | A removed role keeps working until the token expires, and roles live in two places. Roles are business data the core must test and audit |
| **The active tenant in the token** (a Keycloak organization claim) | Switching environment would need a new token, and the core would still have to check the membership |
| **The tenant in a header** instead of the path | Equally safe, but the path makes the scope part of the resource's address: visible in the contract, in logs and to the isolation harness |
| **Slow hashing of API keys** (Argon2, bcrypt) | Protects guessable secrets; a 178-bit key is not guessable, and the hash would cost tens of milliseconds per request and prevent an indexed lookup |
| **Self-contained API keys** (signed JWTs) | Cannot be revoked without a denylist checked on every request, which is the lookup they were meant to avoid |
| **`BYPASSRLS` for jobs** | Exempts a role from every policy on every table. Policies written for a job role are explicit, per table and read-only |
| **A shared store for rate limits** now | Phase 1 runs one instance; Redis is more infrastructure and PostgreSQL a write per request. Documented for scale-out |
| **A hosted identity provider** | See [ADR 0005](../adr/0005-keycloak-for-human-identity.md) |

## 10. Security considerations

These modules are the security boundary of the platform: every other module trusts the caller and tenant they establish. The threats are someone acting with a stolen or forged credential, a caller reaching another tenant's data, a caller gaining permissions it was not given, and floods that exhaust the core.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **Spoofing** | An attacker uses a leaked API key | Keys are recognizable by secret scanners (prefix and checksum) and revocable at once; rolling allows replacement without downtime; `last_used_at` shows whether a key is in use; human-only permissions are never scopes |
| **Spoofing** | An attacker forges or replays an access token | Signature against the realm's keys, fixed algorithm, issuer, audience and expiry checked; access tokens live 5 minutes; refresh tokens never leave the BFF |
| **Spoofing** | An attacker takes over a member's password | Mandatory MFA, brute-force lockout, short sessions; sensitive operations require a recent authentication (step-up) |
| **Tampering** | A caller names another tenant in the path or the body | The body has no tenant field; a path tenant is resolved only through the caller's memberships; API keys ignore it; `WITH CHECK` rejects a row written for another tenant |
| **Tampering** | A member grants themselves a role | Only `members:manage` changes roles, with step-up; DTOs are explicit; the last admin cannot be removed |
| **Repudiation** | A member denies issuing or revoking a key | Every key records its issuer; issuing, rolling, revoking and role changes publish events for the hash-chained audit trail (block G); logs carry the key or member ID and the correlation ID |
| **Information disclosure** | A tenant reads another tenant's data | Four layers: tenant from the credential, a tenant filter in every query, Row-Level Security failing closed, and the isolation harness over every operation; another tenant's resource is a `404` |
| **Information disclosure** | A key or token leaks through logs or URLs | Credentials only in the `Authorization` header; never logged; only the hash is stored; `IssuedApiKey` masks itself; Gitleaks rule for the key format |
| **Information disclosure** | Error responses tell an attacker which keys exist | One `401` for every authentication failure; the reason only in logs and metrics |
| **Denial of service** | A caller floods the API | Per-tenant, per-key and per-member buckets; per-IP limits at the proxy before authentication; authentication is one indexed lookup, and a bad checksum costs no query |
| **Elevation of privilege** | A developer issues a key with more power than they have | A key's scopes are a subset of its issuer's permissions; human-only permissions are never scopes; live keys need step-up like all key operations |
| **Elevation of privilege** | Code runs a query without a tenant, or as another module | No tenant bound means no rows; module roles limit each module to its schema; no role has `BYPASSRLS`; job roles are read-only. Module roles are not a barrier against arbitrary SQL in the service's session (section 5.6): parameterized statements are, checked by Semgrep |

## 11. Delivery

| PR | Delivers | Sections |
|---|---|---|
| 1.17 | Organizations, tenants, members and roles; `TenantId`, `Environment` and `TenantContext` in `shared`; the platform organization; the tenancy role | 3 |
| 1.18 | Keycloak in Compose, the realm as code, MFA and session policy | 4.1 |
| 1.19 | Resource server on `/internal`, tenant resolution from the path, `/internal/me`, `@RequiresPermission`, step-up challenge | 4.1, 4.2, 5.1, 5.2 |
| 1.20 | API key issuance, rolling and revocation, with their endpoints and the Gitleaks rule | 4.3, 7 |
| 1.21 | API key authentication on `/v1` | 4.3, 4.4, 5.1 |
| 1.22 | Row-Level Security, the binding listener, the login role and module roles, the ledger's policies | 5.3–5.6 |
| 1.23 | Tenant isolation harness | 8 |
| 1.24 | Rate limiting | 6 |

Seeding organizations and members for the demo ships with the synthetic data generator (PR 1.66); until then, tests create their own.

## 12. Decisions from review

1. **Roles apply to both environments of an organization.** Live is already guarded by step-up on every key operation and by scopes bounded by the issuer's permissions. If a role per environment is ever needed, a nullable `environment` column on `member_roles` adds it with an expand migration.
2. **Invitations and member management wait for phase 2.** No phase 1 screen or demo step needs them, and an invitation flow (email, acceptance, invitation hijacking) deserves its own design, together with maker-checker. Phase 1 keeps the rule that an organization always has an admin (PR 1.17); demo members come from the synthetic data generator (PR 1.66).
3. **The rate-limit defaults of section 6 stand** as the starting point, revised with the load tests (PR 1.70).
