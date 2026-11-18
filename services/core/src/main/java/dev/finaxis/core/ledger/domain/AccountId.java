package dev.finaxis.core.ledger.domain;

import java.util.UUID;

/** The identifier of a ledger account: its primary key, a UUIDv7. */
public record AccountId(UUID value) {}
