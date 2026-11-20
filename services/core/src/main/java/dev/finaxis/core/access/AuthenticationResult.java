package dev.finaxis.core.access;

import java.util.Optional;

/**
 * The outcome of {@link AccessApi#authenticate} (tenancy design, section 4.4). A refused credential
 * is an expected outcome, so it is a value, not an exception, and the interface is sealed, so a
 * {@code switch} over it must handle each one.
 */
public sealed interface AuthenticationResult {

    /** The credential is a working key; the caller acts on the key's tenant with its scopes. */
    record Authenticated(ApiKeyCaller caller) implements AuthenticationResult {}

    /**
     * The credential authenticates nothing. Every rejection gets the same {@code 401}, so a caller
     * cannot learn whether a key exists, expired or was revoked; the reason and the key are for logs
     * and metrics only.
     *
     * @param reason why the credential was refused
     * @param key the key the credential names, when it names one
     */
    record Rejected(Reason reason, Optional<ApiKeyId> key) implements AuthenticationResult {}

    /** Why a credential was {@link Rejected}. */
    enum Reason {
        /** Not an API key: wrong shape or checksum, found without a database query. */
        MALFORMED,
        /** Shaped like a key, but no key has this secret. */
        UNKNOWN,
        /** The key was revoked, during a roll's grace period or not. */
        REVOKED,
        /** The key was rolled and its grace period has ended. */
        EXPIRED
    }
}
