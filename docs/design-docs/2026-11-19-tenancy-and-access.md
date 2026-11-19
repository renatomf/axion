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
- Invitations and member management screens. Members are provisioned until the console needs them (open question 2).
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
