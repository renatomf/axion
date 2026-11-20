package dev.finaxis.core.access;

import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Permission;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Calls {@link AccessApi} as a controller does: with the caller's tenant bound, so its transactions
 * act for that tenant under Row-Level Security. Authentication binds none, as on {@code /v1}.
 */
public final class TenantBoundAccess implements AccessApi {

    private final AccessApi access;

    public TenantBoundAccess(AccessApi access) {
        this.access = access;
    }

    @Override
    public IssueResult issue(MemberCaller issuer, String name, Set<Permission> scopes) {
        return TenantContext.call(issuer.tenant(), () -> access.issue(issuer, name, scopes));
    }

    @Override
    public List<ApiKeyView> keys(TenantId tenant) {
        return TenantContext.call(tenant, () -> access.keys(tenant));
    }

    @Override
    public Optional<ApiKeyView> revoke(MemberCaller revoker, ApiKeyId key) {
        return TenantContext.call(revoker.tenant(), () -> access.revoke(revoker, key));
    }

    @Override
    public RollResult roll(MemberCaller roller, ApiKeyId key, Duration grace) {
        return TenantContext.call(roller.tenant(), () -> access.roll(roller, key, grace));
    }

    @Override
    public AuthenticationResult authenticate(String credential) {
        return access.authenticate(credential);
    }
}
