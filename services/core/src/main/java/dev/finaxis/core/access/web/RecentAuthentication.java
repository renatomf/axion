package dev.finaxis.core.access.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Step-up (tenancy design, section 4.2): the token's {@code auth_time}, which a refresh keeps, so an
 * old login stays old however fresh the token. A token without it fails closed.
 */
final class RecentAuthentication {

    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private RecentAuthentication() {}

    static boolean isRecent(@Nullable Instant authTime, Clock clock) {
        return authTime != null && !authTime.isBefore(clock.instant().minus(MAX_AGE));
    }
}
