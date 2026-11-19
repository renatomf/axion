package dev.finaxis.core.access.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Whether a member authenticated recently enough for an operation that requires step-up (tenancy
 * design, section 4.2).
 *
 * <p>The moment is the token's {@code auth_time}: when the member last entered their credentials
 * and second factor, not when the token was issued. Keycloak keeps it when it renews a token or
 * reuses a session, so an old login stays old however fresh the token is.
 */
final class RecentAuthentication {

    /** How long ago the member may have authenticated. */
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private RecentAuthentication() {}

    /**
     * @param authTime the token's {@code auth_time}, or {@code null} when the token has none, which
     *     fails closed: a token that does not say when the member authenticated proves nothing
     * @param clock the clock the age is measured with
     */
    static boolean isRecent(@Nullable Instant authTime, Clock clock) {
        return authTime != null && !authTime.isBefore(clock.instant().minus(MAX_AGE));
    }
}
