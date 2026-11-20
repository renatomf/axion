package dev.finaxis.core.access;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static dev.finaxis.core.tenancy.Permission.CHARGES_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AuthenticationResult.Authenticated;
import dev.finaxis.core.access.AuthenticationResult.Reason;
import dev.finaxis.core.access.AuthenticationResult.Rejected;
import dev.finaxis.core.access.domain.ApiKeySecret;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.Role;
import dev.finaxis.core.tenancy.TenancyApi;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link AccessApi#authenticate} against a real PostgreSQL. Each test creates its own organization,
 * and its keys are issued at run time, so no key is ever written in the repository.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AuthenticateApiKeyTest {

    private final AccessApi access;
    private final TenancyApi tenancy;
    private NewOrganization organization;

    @Autowired
    AuthenticateApiKeyTest(AccessApi access, TenancyApi tenancy) {
        this.access = access;
        this.tenancy = tenancy;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void actsOnTheKeysTenantWithItsScopes() {
        var key = issued(Environment.LIVE, Set.of(CHARGES_READ, CHARGES_WRITE));

        assertThat(access.authenticate(key.secret()))
                .isEqualTo(new Authenticated(
                        new ApiKeyCaller(organization.live(), key.id(), Set.of(CHARGES_READ, CHARGES_WRITE))));
    }

    @Test
    void rejectsTextThatIsNotAKey() {
        var key = issued(Environment.TEST, Set.of(CHARGES_READ));
        // The last character changed: the shape still fits, the checksum no longer does.
        String secret = key.secret();
        String mistyped = secret.substring(0, secret.length() - 1) + (secret.endsWith("a") ? "b" : "a");

        assertThat(access.authenticate(mistyped)).isEqualTo(new Rejected(Reason.MALFORMED, Optional.empty()));
        assertThat(access.authenticate("")).isEqualTo(new Rejected(Reason.MALFORMED, Optional.empty()));
    }

    @Test
    void rejectsAWellFormedKeyThatWasNeverIssued() {
        // Generated like an issued key, so its checksum is valid, but never stored.
        String neverIssued =
                ApiKeySecret.generate(Environment.TEST, new SecureRandom()).reveal();

        assertThat(access.authenticate(neverIssued)).isEqualTo(new Rejected(Reason.UNKNOWN, Optional.empty()));
    }

    @Test
    void rejectsARevokedKey() {
        var key = issued(Environment.TEST, Set.of(CHARGES_READ));
        access.revoke(admin(Environment.TEST), key.id());

        assertThat(access.authenticate(key.secret())).isEqualTo(new Rejected(Reason.REVOKED, Optional.of(key.id())));
    }

    @Test
    void acceptsARolledKeyDuringItsGracePeriodOnly() {
        var inGrace = issued(Environment.TEST, Set.of(CHARGES_READ));
        var ended = issued(Environment.TEST, Set.of(CHARGES_READ));
        access.roll(admin(Environment.TEST), inGrace.id(), Duration.ofHours(1));
        access.roll(admin(Environment.TEST), ended.id(), Duration.ZERO);

        assertThat(access.authenticate(inGrace.secret())).isInstanceOf(Authenticated.class);
        assertThat(access.authenticate(ended.secret()))
                .isEqualTo(new Rejected(Reason.EXPIRED, Optional.of(ended.id())));
    }

    @Test
    void rejectsAKeyRevokedDuringItsGracePeriod() {
        var key = issued(Environment.TEST, Set.of(CHARGES_READ));
        access.roll(admin(Environment.TEST), key.id(), Duration.ofHours(1));
        access.revoke(admin(Environment.TEST), key.id());

        assertThat(access.authenticate(key.secret())).isEqualTo(new Rejected(Reason.REVOKED, Optional.of(key.id())));
    }

    /** The caller tenant resolution would build for the organization's admin. */
    private MemberCaller admin(Environment environment) {
        var tenant = environment == Environment.TEST ? organization.test() : organization.live();
        return new MemberCaller(tenant, environment, organization.admin(), Role.permissionsOf(Set.of(Role.ADMIN)));
    }

    private IssuedApiKey issued(Environment environment, Set<Permission> scopes) {
        var result = access.issue(admin(environment), "Checkout", scopes);
        assertThat(result).isInstanceOf(IssueResult.Issued.class);
        return ((IssueResult.Issued) result).key();
    }
}
