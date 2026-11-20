package dev.finaxis.core.access;

import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
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
    private final TenancyApi tenancy;

    public TenantBoundAccess(AccessApi access, TenancyApi tenancy) {
        this.access = access;
        this.tenancy = tenancy;
    }

    @Override
    public IssueResult issue(MemberCaller issuer, String name, Set<Permission> scopes) {
        return TenantContext.call(issuer.tenant(), issuer.environment(), () -> access.issue(issuer, name, scopes));
    }

    @Override
    public List<ApiKeyView> keys(TenantId tenant) {
        var environment = tenancy.tenant(tenant).orElseThrow().environment();
        return TenantContext.call(tenant, environment, () -> access.keys(tenant));
    }

    @Override
    public Optional<ApiKeyView> revoke(MemberCaller revoker, ApiKeyId key) {
        return TenantContext.call(revoker.tenant(), revoker.environment(), () -> access.revoke(revoker, key));
    }

    @Override
    public RollResult roll(MemberCaller roller, ApiKeyId key, Duration grace) {
        return TenantContext.call(roller.tenant(), roller.environment(), () -> access.roll(roller, key, grace));
    }

    @Override
    public AuthenticationResult authenticate(String credential) {
        return access.authenticate(credential);
    }
}
