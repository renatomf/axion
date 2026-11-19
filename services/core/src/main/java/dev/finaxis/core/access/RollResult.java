package dev.finaxis.core.access;

import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/**
 * The outcome of {@link AccessApi#roll}. Expected outcomes are values, not exceptions (overview
 * design, section 19.1), and the interface is sealed, so a {@code switch} over it must handle each
 * one.
 */
public sealed interface RollResult {

    /**
     * The key was rolled.
     *
     * @param replacement the new key, with its secret, which is in the result and nowhere else
     * @param rolled the old key as it is now, working until its {@code expiresAt}
     */
    record Rolled(IssuedApiKey replacement, ApiKeyView rolled) implements RollResult {}

    /** The member's tenant has no key with this ID, so nothing was rolled. */
    record KeyNotFound() implements RollResult {}

    /**
     * The key no longer works, or soon will not, so nothing was rolled: it was revoked, or it was
     * already rolled, whether or not its grace period has ended. A key is rolled at most once; its
     * replacement is the key to roll next.
     */
    record KeyNotRollable() implements RollResult {}

    /**
     * The member lacks some of the key's scopes, so nothing was rolled: the replacement is a new
     * key, and a key never does more than the member who issued it could (tenancy design, section
     * 3.3). A member with fewer permissions can issue a narrower key instead.
     *
     * @param missing the key's scopes the member lacks; the set cannot be changed
     */
    record ScopesNotHeld(Set<Permission> missing) implements RollResult {

        public ScopesNotHeld {
            missing = Set.copyOf(missing);
        }
    }

    /**
     * The tenant already has {@value AccessApi#MAX_ACTIVE_KEYS} keys that work, and the old key
     * would keep working beside its replacement during the grace period, so nothing was rolled.
     * Rolling without a grace period, or revoking another key, makes room.
     */
    record LimitReached(int limit) implements RollResult {}
}
