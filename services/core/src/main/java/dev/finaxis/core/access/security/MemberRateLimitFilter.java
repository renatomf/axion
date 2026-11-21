package dev.finaxis.core.access.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the member's budget to an {@code /internal} request whose access token was accepted
 * (tenancy design, section 6) and reports it in the {@code RateLimit} headers; a request beyond it
 * gets {@code 429 rate_limited}. A request without an accepted token passes through untouched, to
 * be refused with {@code 401} further on.
 */
// Not a bean: Spring Boot would also register it with the servlet container, for every path.
final class MemberRateLimitFilter extends OncePerRequestFilter {

    private final RateLimits limits;
    private final ProblemDetailsSecurityHandler rejections;

    MemberRateLimitFilter(RateLimits limits, ProblemDetailsSecurityHandler rejections) {
        this.limits = limits;
        this.rejections = rejections;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            String subject = Objects.requireNonNull(token.getToken().getSubject());
            RateLimits.Decision decision = limits.tryAcquire(subject);
            RateLimitHeaders.write(response, decision.quotas());
            if (decision.retryAfter().isPresent()) {
                rejections.rateLimited(request, response, decision.retryAfter().get());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
