/**
 * The ledger's persistence: explicit SQL with Spring's {@code JdbcClient}, rows mapped to domain
 * types by hand (ADR 0004). Every statement the invariants rely on is written here, in full.
 */
@NullMarked
package dev.finaxis.core.ledger.persistence;

import org.jspecify.annotations.NullMarked;
