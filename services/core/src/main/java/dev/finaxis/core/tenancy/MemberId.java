package dev.finaxis.core.tenancy;

import java.util.UUID;

/**
 * The identifier of a member: one person's place in one organization (tenancy design, section
 * 3.1). A person in two organizations is two members.
 *
 * @param value the member's primary key, a UUIDv7
 */
public record MemberId(UUID value) {}
