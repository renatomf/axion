package dev.finaxis.core.billing;

import java.util.UUID;

/** The identifier of a refund: a UUIDv7, shown by the API as {@code re_…}. */
public record RefundId(UUID value) {}
