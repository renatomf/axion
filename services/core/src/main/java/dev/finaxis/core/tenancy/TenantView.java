package dev.finaxis.core.tenancy;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;

/** A tenant as other modules see it. */
public record TenantView(TenantId id, OrganizationId organization, Environment environment, TenantStatus status) {}
