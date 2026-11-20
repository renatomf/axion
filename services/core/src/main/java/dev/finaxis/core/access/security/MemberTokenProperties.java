package dev.finaxis.core.access.security;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * @param issuer the Keycloak realm's issuer URL
 * @param clients the Keycloak clients whose access tokens the core accepts
 */
@ConfigurationProperties("finaxis.members")
record MemberTokenProperties(String issuer, Set<String> clients) {

    MemberTokenProperties {
        Assert.hasText(issuer, "finaxis.members.issuer must be set");
        Assert.notEmpty(clients, "finaxis.members.clients must name at least one client");
        clients = Set.copyOf(clients);
    }
}
