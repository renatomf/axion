package dev.finaxis.core.access;

import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/** The outcome of {@link AccessApi#issue}. */
public sealed interface IssueResult {

    record Issued(IssuedApiKey key) implements IssueResult {}

    /** The tenant already has {@value AccessApi#MAX_ACTIVE_KEYS} working keys. */
    record LimitReached(int limit) implements IssueResult {}

    /** A key never does more than its issuer could (tenancy design, section 3.3). */
    record ScopesNotHeld(Set<Permission> missing) implements IssueResult {

        public ScopesNotHeld {
            missing = Set.copyOf(missing);
        }
    }
}
