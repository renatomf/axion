package dev.finaxis.core.access;

import java.util.Optional;

/** The outcome of {@link AccessApi#authenticate} (tenancy design, section 4.4). */
public sealed interface AuthenticationResult {

    record Authenticated(ApiKeyCaller caller) implements AuthenticationResult {}

    /** A working key of a suspended or closed tenant: the caller proved it holds the key, so may learn this. */
    record TenantInactive(ApiKeyId key) implements AuthenticationResult {}

    /** The reason and key are for logs and metrics only; the caller gets the same {@code 401}. */
    record Rejected(Reason reason, Optional<ApiKeyId> key) implements AuthenticationResult {}

    enum Reason {
        /** Wrong shape or checksum, found without a query. */
        MALFORMED,
        UNKNOWN,
        REVOKED,
        /** Rolled, and its grace period has ended. */
        EXPIRED
    }
}
