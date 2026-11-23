package dev.axion.core.access.security;

import dev.axion.core.access.ApiKeyCaller;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * A request authenticated with a working API key. It grants no Spring authorities: scopes are
 * checked against each endpoint's {@code @RequiresPermission}. The secret is not kept.
 */
// Never serialized: the core creates no HTTP session.
@SuppressWarnings("serial")
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final ApiKeyCaller caller;

    ApiKeyAuthentication(ApiKeyCaller caller) {
        super(List.of());
        this.caller = caller;
        setAuthenticated(true);
    }

    @Override
    public ApiKeyCaller getPrincipal() {
        return caller;
    }

    @Override
    public @Nullable Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return caller.key().publicId().toString();
    }
}
