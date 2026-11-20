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
 * (tenancy design, sections 4.3 and 4.4). Every refused credential gets the same {@code 401}, so a
 * caller cannot learn whether a key exists, expired or was revoked; a working key of an inactive
 * tenant gets {@code 403 tenant_inactive}.
 */
// Not a bean: Spring Boot would also register it with the servlet container, for every path.
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
