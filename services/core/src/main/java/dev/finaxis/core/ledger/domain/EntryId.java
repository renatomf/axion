package dev.finaxis.core.ledger.domain;

import java.util.UUID;

/** The identifier of a ledger entry: its primary key, a UUIDv7. */
public record EntryId(UUID value) {}
