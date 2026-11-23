package dev.axion.core.access.web;

import dev.axion.core.access.NoPermissionRequired;
import dev.axion.core.tenancy.TenancyApi;
import java.util.Objects;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CurrentMemberController {

    private final TenancyApi tenancy;

    CurrentMemberController(TenancyApi tenancy) {
        this.tenancy = tenancy;
    }

    @NoPermissionRequired(reason = "Lists only the caller's own memberships, and acts on no tenant")
    @GetMapping("/internal/me")
    CurrentMemberResponse currentMember(@AuthenticationPrincipal Jwt token) {
        // MemberTokenValidation rejects a token without a subject.
        String subject = Objects.requireNonNull(token.getSubject());
        return CurrentMemberResponse.of(tenancy.memberships(subject));
    }
}
