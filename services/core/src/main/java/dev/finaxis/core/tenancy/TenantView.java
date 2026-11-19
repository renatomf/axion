package dev.finaxis.core.tenancy;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;

/**
 * A tenant as other modules see it: which organization and environment it is, and whether it may
 * be used.
 *
 * @param id the tenant
 * @param organization the organization it belongs to
 * @param environment its environment; an organization has one tenant per environment
 * @param status whether it accepts requests
 */
public record TenantView(TenantId id, OrganizationId organization, Environment environment, TenantStatus status) {}
