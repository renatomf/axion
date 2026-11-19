package dev.finaxis.core.tenancy;

import java.util.UUID;

/**
 * The identifier of an organization: a business that uses the platform, with one tenant per
 * environment (tenancy design, section 3.1).
 *
 * @param value the organization's primary key, a UUIDv7
 */
public record OrganizationId(UUID value) {}
