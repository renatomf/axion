package dev.finaxis.core.idempotency;

/** The outcome of {@link IdempotencyApi#execute}. */
public sealed interface IdempotentResult {

    /** The operation ran, and this is its response. */
    record Executed(CapturedResponse response) implements IdempotentResult {}

    /** The request repeats one that committed; the operation did not run again. */
    record Replayed(StoredResponse response) implements IdempotentResult {}

    /** A request with the key is still running; the operation did not run. Answered with 409. */
    record InProgress() implements IdempotentResult {}

    /** The key was used for a request with another fingerprint; the operation did not run. Answered with 422. */
    record KeyReused() implements IdempotentResult {}
}
