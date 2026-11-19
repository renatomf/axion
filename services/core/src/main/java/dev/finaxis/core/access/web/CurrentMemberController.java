package dev.finaxis.core.access.web;

import dev.finaxis.core.access.NoPermissionRequired;
import dev.finaxis.core.tenancy.TenancyApi;
import java.util.Objects;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/me}: the organizations and tenants of the calling member, for the console's
 * organization and environment switcher (tenancy design, section 5.1; contract in {@code
 * openapi/internal.yaml}).
 *
 * <p>{@code @RestController} makes Spring MVC route requests to the {@code @GetMapping} methods
 * below and write what they return as JSON. The request reaches this class only after the {@code
 * /internal} filter chain of {@code access.security} has validated the access token.
 */
@RestController
class CurrentMemberController {

    private final TenancyApi tenancy;

    CurrentMemberController(TenancyApi tenancy) {
        this.tenancy = tenancy;
    }

    /**
     * @param token the validated access token: {@code @AuthenticationPrincipal} asks Spring Security
     *     for the principal it authenticated the request with, which for a bearer token is the
     *     decoded {@link Jwt}
     */
    @NoPermissionRequired(reason = "Lists only the caller's own memberships, and acts on no tenant")
    @GetMapping("/internal/me")
    CurrentMemberResponse currentMember(@AuthenticationPrincipal Jwt token) {
        // MemberTokenValidation rejects a token without a subject, so it is never null here.
        String subject = Objects.requireNonNull(token.getSubject());
        return CurrentMemberResponse.of(tenancy.memberships(subject));
    }
}
