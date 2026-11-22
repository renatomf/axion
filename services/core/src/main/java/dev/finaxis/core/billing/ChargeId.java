package dev.finaxis.core.billing;

import java.util.UUID;

/** The identifier of a charge: its primary key, a UUIDv7, shown by the API as {@code ch_…}. */
public record ChargeId(UUID value) {}
