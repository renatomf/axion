package dev.finaxis.core.access.security;

import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AuthenticationResult.Authenticated;
import dev.finaxis.core.access.AuthenticationResult.Rejected;
import dev.finaxis.core.access.AuthenticationResult.TenantInactive;
import dev.finaxis.core.access.security.AuthenticationFailures.Failure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a {@code /v1} request with the API key in its {@code Authorization} header
 * (tenancy design, sections 4.3 and 4.4).
 *
 * <p>For each request, in the {@code /v1} filter chain:
 *
 * <ul>
 *   <li>no {@code Bearer} credential in the {@code Authorization} header, or no header: {@code
 *       401}, since every {@code /v1} request needs a key;
 *   <li>a header that is not a single {@code Bearer} credential: {@code 401};
 *   <li>a credential {@link AccessApi#authenticate} rejects, whatever the reason: {@code 401}, the
 *       same answer, so a caller cannot learn whether a key exists, expired or was revoked;
 *   <li>a working key of a tenant that is not active: {@code 403 tenant_inactive};
 *   <li>a working key: the request continues as an {@link ApiKeyAuthentication}.
 * </ul>
 *
 * <p>Each {@code 401} is recorded by {@link AuthenticationFailures}.
 *
 * <p>The header is read by Spring's {@link DefaultBearerTokenResolver}, the one the {@code
 * /internal} chain uses for access tokens, so both APIs parse it alike: the header only, never the
 * query string or the body, and a request with two credentials is refused.
 *
 * <p>{@link OncePerRequestFilter} runs the filter once per request, not again when the request is
 * forwarded to the error page. The filter is deliberately not a Spring bean: Spring Boot would also
 * register a bean of type {@code Filter} with the servlet container, for every path; created inside
 * {@link SecurityConfiguration}, it runs in the {@code /v1} chain only.
 */
final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final AccessApi access;
    private final ProblemDetailsSecurityHandler rejections;
    private final AuthenticationFailures failures;
    private final BearerTokenResolver bearer = new DefaultBearerTokenResolver();
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    ApiKeyAuthenticationFilter(
            AccessApi access, ProblemDetailsSecurityHandler rejections, AuthenticationFailures failures) {
        this.access = access;
        this.rejections = rejections;
        this.failures = failures;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        @Nullable String credential;
        try {
            credential = bearer.resolve(request);
        } catch (AuthenticationException malformedHeader) {
            failures.record(Failure.MALFORMED, Optional.empty());
            rejections.commence(request, response, malformedHeader);
            return;
        }
        if (credential == null) {
            // Answered here, not by the chain's rule, so that it is recorded like any rejection.
            failures.record(Failure.MISSING, Optional.empty());
            rejections.commence(request, response, new InsufficientAuthenticationException("No API key"));
            return;
        }
        switch (access.authenticate(credential)) {
            case Authenticated(var caller) -> {
                SecurityContext context = contexts.createEmptyContext();
                context.setAuthentication(new ApiKeyAuthentication(caller));
                contexts.setContext(context);
                chain.doFilter(request, response);
            }
            case Rejected(var reason, var key) -> {
                failures.record(Failure.of(reason), key);
                rejections.commence(request, response, new BadCredentialsException("Invalid API key"));
            }
            case TenantInactive _ -> rejections.tenantInactive(request, response);
        }
    }
}
