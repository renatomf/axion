package dev.finaxis.core.idempotency;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Runs a public API write at most once per idempotency key of a tenant, and answers its repeats from
 * the stored response (idempotency design, sections 4 and 7).
 */
public interface IdempotencyApi {

    /** How long a key is honored at least: a repeat within it replays (section 6.1). */
    Duration RETENTION = Duration.ofHours(24);

    /**
     * Runs the operation unless the key was used before, in a transaction this call opens: the
     * operation's effect and the key's stored response commit together or not at all. A response
     * with a status of 500 or more is not stored, and rolls the operation back with it; so does an
     * operation that marked the transaction rollback-only. An exception from the operation rolls back
     * and propagates.
     *
     * @throws IllegalStateException if called inside a transaction already open, since the key must
     *     commit with the operation's effect and nothing else
     */
    IdempotentResult execute(IdempotentRequest request, Supplier<CapturedResponse> operation);
}
