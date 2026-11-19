package dev.finaxis.core.access;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Where members' access tokens come from, bound from the {@code finaxis.members} properties.
 *
 * <p>{@code @ConfigurationProperties} makes Spring Boot fill this record from the configuration
 * (YAML files, environment variables such as {@code FINAXIS_MEMBERS_ISSUER}) when the application
 * starts. Missing values stop the startup, instead of leaving the core unable to authenticate
 * anyone.
 *
 * @param issuer the Keycloak realm's issuer URL, such as {@code
 *     http://localhost:8180/realms/finaxis}; the core reads the realm's keys from it
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
