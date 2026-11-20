package dev.finaxis.core.tenancy;

import java.util.UUID;

/** One person's place in one organization: a person in two organizations is two members. */
public record MemberId(UUID value) {}
