package dev.finaxis.core.access;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static dev.finaxis.core.tenancy.Permission.CHARGES_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AuthenticationResult.Authenticated;
import dev.finaxis.core.access.AuthenticationResult.Reason;
import dev.finaxis.core.access.AuthenticationResult.Rejected;
import dev.finaxis.core.access.AuthenticationResult.TenantInactive;
import dev.finaxis.core.access.domain.ApiKeySecret;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AccessApi#authenticate} against a real PostgreSQL. Each test creates its own organization,
 * and its keys are issued at run time, so no key is ever written in the repository.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AuthenticateApiKeyTest {

    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient owner;
    private NewOrganization organization;

    @Autowired
    AuthenticateApiKeyTest(AccessApi access, TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void actsOnTheKeysTenantWithItsScopes() {
        var key = issued(Environment.LIVE, Set.of(CHARGES_READ, CHARGES_WRITE));

        assertThat(access.authenticate(key.secret()))
                .isEqualTo(new Authenticated(new ApiKeyCaller(
                        organization.live(), Environment.LIVE, key.id(), Set.of(CHARGES_READ, CHARGES_WRITE))));
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

    @Test
    void tellsAKeyOfAnInactiveTenantApart() {
        var key = issued(Environment.TEST, Set.of(CHARGES_READ));
        owner.sql("UPDATE tenancy.tenants SET status = 'SUSPENDED' WHERE id = ?")
                .param(organization.test().value())
                .update();

        assertThat(access.authenticate(key.secret())).isEqualTo(new TenantInactive());
    }

    @Test
    void rejectsARevokedKeyOfAnInactiveTenant() {
        var key = issued(Environment.TEST, Set.of(CHARGES_READ));
        access.revoke(admin(Environment.TEST), key.id());
        owner.sql("UPDATE tenancy.tenants SET status = 'CLOSED' WHERE id = ?")
                .param(organization.test().value())
                .update();

        assertThat(access.authenticate(key.secret())).isEqualTo(new Rejected(Reason.REVOKED, Optional.of(key.id())));
    }

    @Test
    void recordsTheFirstUseOfAKey() {
        var key = issued(Environment.TEST, Set.of(CHARGES_READ));
        // Truncated as the service truncates its clock, so a use in the same microsecond is between.
        var before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        access.authenticate(key.secret());

        var after = Instant.now();
        assertThat(lastUsedAt(key)).hasValueSatisfying(used -> assertThat(used).isBetween(before, after));
    }

    @Test
    void leavesARecentUseAsItIs() {
        var key = issued(Environment.TEST, Set.of(CHARGES_READ));
        var recent = setLastUsedAt(key, Instant.now().minus(Duration.ofMinutes(30)));

        access.authenticate(key.secret());

        assertThat(lastUsedAt(key)).contains(recent);
    }

    @Test
    void recordsAUseOnceTheLastIsAnHourOld() {
        var key = issued(Environment.TEST, Set.of(CHARGES_READ));
        var old = setLastUsedAt(key, Instant.now().minus(Duration.ofHours(2)));

        access.authenticate(key.secret());

        assertThat(lastUsedAt(key)).hasValueSatisfying(used -> assertThat(used).isAfter(old.plus(Duration.ofHours(1))));
    }

    @Test
    void recordsNoUseForAKeyThatAuthenticatesNothing() {
        var revoked = issued(Environment.TEST, Set.of(CHARGES_READ));
        access.revoke(admin(Environment.TEST), revoked.id());
        var ofInactiveTenant = issued(Environment.LIVE, Set.of(CHARGES_READ));
        owner.sql("UPDATE tenancy.tenants SET status = 'SUSPENDED' WHERE id = ?")
                .param(organization.live().value())
                .update();

        access.authenticate(revoked.secret());
        access.authenticate(ofInactiveTenant.secret());

        assertThat(lastUsedAt(revoked)).isEmpty();
        assertThat(lastUsedAt(ofInactiveTenant)).isEmpty();
    }

    private Optional<Instant> lastUsedAt(IssuedApiKey key) {
        return owner.sql("SELECT last_used_at FROM access.api_keys WHERE id = ?")
                .param(key.id().value())
                .query((row, rowNumber) -> Optional.ofNullable(row.getObject(1, OffsetDateTime.class)))
                .single()
                .map(OffsetDateTime::toInstant);
    }

    /** Writes the key's last use, truncated as PostgreSQL stores it, and returns what was written. */
    private Instant setLastUsedAt(IssuedApiKey key, Instant value) {
        var stored = value.truncatedTo(ChronoUnit.MICROS);
        owner.sql("UPDATE access.api_keys SET last_used_at = ? WHERE id = ?")
                .params(stored.atOffset(ZoneOffset.UTC), key.id().value())
                .update();
        return stored;
    }

    private MemberCaller admin(Environment environment) {
        return AccessFixtures.admin(organization, environment);
    }

    private IssuedApiKey issued(Environment environment, Set<Permission> scopes) {
        return AccessFixtures.issue(access, admin(environment), "Checkout", scopes);
    }
}
