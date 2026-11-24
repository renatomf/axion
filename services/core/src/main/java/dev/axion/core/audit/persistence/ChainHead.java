package dev.axion.core.audit.persistence;

import dev.axion.core.audit.domain.ChainHash;

/** A chain's last record, by its sequence and hash; sequence 0 and 32 zero bytes before the first. */
public record ChainHead(long sequence, ChainHash hash) {}
