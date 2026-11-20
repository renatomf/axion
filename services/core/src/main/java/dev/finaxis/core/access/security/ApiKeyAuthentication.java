package dev.finaxis.core.access.security;

import dev.finaxis.core.access.ApiKeyCaller;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * A request authenticated with a working API key, as Spring Security keeps it in the {@code
 * SecurityContext} for the rest of the request.
 *
 * <p>Spring Security represents whoever is calling as an {@code Authentication}; the {@code /v1}
 * filter chain only checks that one is present. The principal is the {@link ApiKeyCaller}, so the
 * permission check further on reads the tenant and the scopes from it. The token grants no Spring
 * authorities: the scopes are checked against each endpoint's {@code @RequiresPermission}, not by
 * Spring Security rules.
 *
 * <p>The secret is not kept: {@link #getCredentials()} is {@code null}, so it cannot leak from here
 * into a log or an error.
 *
 * <p>Every Spring {@code Authentication} is {@code Serializable}, for servers that store it in an
 * HTTP session. The core creates no session, so this one is never serialized, and the compiler's
 * warning about serialization is suppressed instead of making the caller serializable.
 */
@SuppressWarnings("serial")
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final ApiKeyCaller caller;

    ApiKeyAuthentication(ApiKeyCaller caller) {
        super(List.of());
        this.caller = caller;
        setAuthenticated(true);
    }

    /** The key's tenant, ID and scopes. */
    @Override
    public ApiKeyCaller getPrincipal() {
        return caller;
    }

    /** Always {@code null}: the secret is dropped once the key is found. */
    @Override
    public @Nullable Object getCredentials() {
        return null;
    }

    /** The key's public ID, which Spring Security uses as the caller's name. */
    @Override
    public String getName() {
        return caller.key().publicId().toString();
    }
}
