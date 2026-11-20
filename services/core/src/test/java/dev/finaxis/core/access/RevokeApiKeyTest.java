package dev.finaxis.core.access;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseRole;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AccessApi#revoke} against a real PostgreSQL. Each test creates its own organization, so it
 * never meets another test's keys.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class RevokeApiKeyTest {

    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient jdbc;
    private NewOrganization organization;

    @Autowired
    RevokeApiKeyTest(AccessApi access, TenancyApi tenancy, JdbcClient jdbc) {
        this.access = access;
        this.tenancy = tenancy;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void recordsWhenTheKeyWasRevoked() {
        var key = issued(Environment.TEST);

        var revoked = access.revoke(admin(Environment.TEST), key.id()).orElseThrow();

        assertThat(revoked.revokedAt()).hasValueSatisfying(at -> assertThat(at).isAfterOrEqualTo(key.createdAt()));
        assertThat(access.keys(organization.test())).containsExactly(revoked);
    }

    @Test
    void keepsTheFirstRevocationWhenRevokedAgain() {
        var key = issued(Environment.TEST);
        var first = access.revoke(admin(Environment.TEST), key.id()).orElseThrow();

        var second = access.revoke(admin(Environment.TEST), key.id()).orElseThrow();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void treatsAKeyOfAnotherTenantAsUnknown() {
        var live = issued(Environment.LIVE);

        assertThat(access.revoke(admin(Environment.TEST), live.id())).isEmpty();
        assertThat(access.keys(organization.live()).getFirst().revokedAt()).isEmpty();
    }

    @Test
    void findsNothingToRevokeForAnUnknownKey() {
        assertThat(access.revoke(admin(Environment.TEST), new ApiKeyId(UUID.randomUUID())))
                .isEmpty();
    }

    /** As {@code finaxis_access}, the code needs no privilege beyond the role's grants. */
    @Test
    @Transactional
    void needsNoMoreThanTheAccessRolesGrants() {
        var key = issued(Environment.TEST);
        DatabaseRole.ACCESS.actAs(jdbc, organization.test());

        assertThat(access.revoke(admin(Environment.TEST), key.id())).isPresent();
    }

    private MemberCaller admin(Environment environment) {
        return AccessFixtures.admin(organization, environment);
    }

    private IssuedApiKey issued(Environment environment) {
        return AccessFixtures.issue(access, admin(environment), "Checkout", Set.of(CHARGES_READ));
    }
}
