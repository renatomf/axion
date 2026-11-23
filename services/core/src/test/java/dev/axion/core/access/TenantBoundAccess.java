package dev.axion.core.access;

import dev.axion.core.TestCaller;
import dev.axion.core.shared.ActorContext;
import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.TenancyApi;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Calls {@link AccessApi} as a controller does: with the caller's tenant bound, so its transactions
 * act for that tenant under Row-Level Security, and the caller as the actor of its events.
 * Authentication binds none, as on {@code /v1}.
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
        return bound(issuer, () -> access.issue(issuer, name, scopes));
    }

    @Override
    public List<ApiKeyView> keys(TenantId tenant) {
        var environment = tenancy.tenant(tenant).orElseThrow().environment();
        return TenantContext.call(tenant, environment, () -> access.keys(tenant));
    }

    @Override
    public Optional<ApiKeyView> revoke(MemberCaller revoker, ApiKeyId key) {
        return bound(revoker, () -> access.revoke(revoker, key));
    }

    @Override
    public RollResult roll(MemberCaller roller, ApiKeyId key, Duration grace) {
        return bound(roller, () -> access.roll(roller, key, grace));
    }

    @Override
    public AuthenticationResult authenticate(String credential) {
        return access.authenticate(credential);
    }

    private static <T> T bound(MemberCaller caller, ScopedValue.CallableOp<T, RuntimeException> work) {
        return TenantContext.call(
                caller.tenant(),
                caller.environment(),
                () -> ActorContext.call(caller.actor(), TestCaller.CORRELATION_ID, work));
    }
}
