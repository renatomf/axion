package dev.axion.core.ledger;

import java.util.UUID;

/** The identifier of a posting: its primary key, a UUIDv7. */
public record PostingId(UUID value) {}
