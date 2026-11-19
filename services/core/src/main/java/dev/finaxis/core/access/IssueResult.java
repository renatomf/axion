package dev.finaxis.core.access;

import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/**
 * The outcome of {@link AccessApi#issue}. Expected outcomes are values, not exceptions (overview
 * design, section 19.1), and the interface is sealed, so a {@code switch} over it must handle each
 * one.
 */
public sealed interface IssueResult {

    /** The key was issued; its secret is in the result and nowhere else. */
    record Issued(IssuedApiKey key) implements IssueResult {}

    /**
     * The tenant already has {@value AccessApi#MAX_ACTIVE_KEYS} keys that work, so nothing was
     * issued. Revoking one makes room.
     */
    record LimitReached(int limit) implements IssueResult {}

    /**
     * The issuer asked for scopes they do not have, so nothing was issued: a key never does more than
     * the member who issued it could (tenancy design, section 3.3).
     *
     * @param missing the requested scopes the issuer lacks; the set cannot be changed
     */
    record ScopesNotHeld(Set<Permission> missing) implements IssueResult {

        public ScopesNotHeld {
            missing = Set.copyOf(missing);
        }
    }
}
