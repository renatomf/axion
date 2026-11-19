-- The tenancy directory (tenancy design, section 3.4): organizations, their
-- two tenants (one per environment), members and their roles. These tables
-- define the tenants, so they carry no tenant_id and get no Row-Level Security
-- policy; other modules reach them only through TenancyApi.
CREATE SCHEMA tenancy;

CREATE TABLE tenancy.organizations (
    id           uuid        PRIMARY KEY,
    name         text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    is_platform  boolean     NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- At most one platform organization: the index covers only the rows where
-- is_platform is true, and they all share the key true.
CREATE UNIQUE INDEX organizations_one_platform_idx ON tenancy.organizations (is_platform) WHERE is_platform;

CREATE TABLE tenancy.tenants (
    id               uuid        PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES tenancy.organizations,
    environment      text        NOT NULL CHECK (environment IN ('TEST', 'LIVE')),
    status           text        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    -- One tenant per environment, so at most two per organization.
    UNIQUE (organization_id, environment)
);

CREATE TABLE tenancy.members (
    id               uuid        PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES tenancy.organizations,
    -- The Keycloak user's `sub`: a stable identifier, not personal data.
    user_subject     text        NOT NULL CHECK (char_length(user_subject) BETWEEN 1 AND 255),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_subject)
);

-- Authentication finds a user's memberships by their subject (section 5.1).
CREATE INDEX members_user_subject_idx ON tenancy.members (user_subject);

CREATE TABLE tenancy.member_roles (
    member_id  uuid NOT NULL REFERENCES tenancy.members,
    role       text NOT NULL CHECK (role IN ('ADMIN', 'FINANCE', 'APPROVER', 'DEVELOPER', 'READ_ONLY')),
    PRIMARY KEY (member_id, role)
);
