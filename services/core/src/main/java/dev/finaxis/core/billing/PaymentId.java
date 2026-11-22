package dev.finaxis.core.billing;

import java.util.UUID;

/** The identifier of a payment a charge received: a UUIDv7, shown by the API as {@code pmt_…}. */
public record PaymentId(UUID value) {}
