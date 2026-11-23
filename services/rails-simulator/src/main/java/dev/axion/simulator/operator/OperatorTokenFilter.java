package dev.axion.simulator.operator;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request that carries the operator token as {@code Authorization: Bearer <token>}.
 * A request without the header goes on unauthenticated, and is refused by the rules that follow; one
 * with another token is refused here.
 */
class OperatorTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final byte[] tokenDigest;
    private final AuthenticationEntryPoint rejections;

    OperatorTokenFilter(OperatorProperties properties, AuthenticationEntryPoint rejections) {
        this.tokenDigest = sha256(properties.token());
        this.rejections = rejections;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!authorization.regionMatches(true, 0, BEARER, 0, BEARER.length()) || !matches(authorization)) {
            SecurityContextHolder.clearContext();
            rejections.commence(request, response, new BadCredentialsException("Not the operator token"));
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("operator", null, List.of()));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    /** Compares digests of equal length in constant time, so the time taken reveals neither the token nor its length. */
    private boolean matches(String authorization) {
        return MessageDigest.isEqual(tokenDigest, sha256(authorization.substring(BEARER.length())));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}
