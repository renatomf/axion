package dev.axion.core.audit.domain;

/** A record as stored in its chain: its fields, the hash it links to and its own hash. */
public record ChainedRecord(AuditRecord record, ChainHash previous, ChainHash hash) {}
