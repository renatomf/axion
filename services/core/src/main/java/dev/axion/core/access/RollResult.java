package dev.axion.core.access;

import dev.axion.core.tenancy.Permission;
import java.util.Set;

/** The outcome of {@link AccessApi#roll}. */
public sealed interface RollResult {

    /** The replacement, and the old key, which works until its {@code expiresAt}. */
    record Rolled(IssuedApiKey replacement, ApiKeyView rolled) implements RollResult {}

    record KeyNotFound() implements RollResult {}

    /** Revoked or already rolled: a key rolls once, and its replacement is the one to roll next. */
    record KeyNotRollable() implements RollResult {}

    /** The replacement is a new key, so it may not exceed the roller's permissions. */
    record ScopesNotHeld(Set<Permission> missing) implements RollResult {

        public ScopesNotHeld {
            missing = Set.copyOf(missing);
        }
    }

    /** The old key and its replacement would both count during the grace period. */
    record LimitReached(int limit) implements RollResult {}
}
