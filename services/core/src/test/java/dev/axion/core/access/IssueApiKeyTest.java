package dev.axion.core.access;

import static dev.axion.core.tenancy.Permission.API_KEYS_MANAGE;
import static dev.axion.core.tenancy.Permission.CHARGES_READ;
import static dev.axion.core.tenancy.Permission.CHARGES_WRITE;
import static dev.axion.core.tenancy.Permission.REFUNDS_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.access.domain.ApiKeySecret;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Role;
import dev.axion.core.tenancy.TenancyApi;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AccessApi#issue} against a real PostgreSQL. Each test creates its own organization, so it
 * never meets another test's keys.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class IssueApiKeyTest {

    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient owner;
    private NewOrganization organization;

    @Autowired
    IssueApiKeyTest(AccessApi access, TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void storesOnlyTheHashOfTheSecretItReturns() {
        var key =
                AccessFixtures.issue(access, admin(Environment.LIVE), "Checkout", Set.of(CHARGES_READ, CHARGES_WRITE));

        var secret = ApiKeySecret.parse(key.secret()).orElseThrow();
        var stored = owner.sql("""
                        SELECT tenant_id, name, secret_hash, last_four, array_to_string(scopes, ' ') AS scopes, created_by
                          FROM access.api_keys
                         WHERE id = ?
                        """).param(key.id().value()).query().singleRow();
        assertThat(secret.environment()).isEqualTo(Environment.LIVE);
        assertThat(stored)
                .containsEntry("tenant_id", organization.live().value())
                .containsEntry("name", "Checkout")
                .containsEntry("secret_hash", secret.hash())
                .containsEntry("last_four", key.lastFour())
                .containsEntry("scopes", "charges:read charges:write")
                .containsEntry("created_by", organization.admin().value());
    }

    @Test
    void returnsTheIssueInstantThatIsStored() {
        var key = AccessFixtures.issue(access, admin(Environment.TEST), "Checkout", Set.of(CHARGES_READ));

        var stored = owner.sql("SELECT created_at FROM access.api_keys WHERE id = ?")
                .param(key.id().value())
                .query(OffsetDateTime.class)
                .single();
        assertThat(stored.toInstant()).isEqualTo(key.createdAt());
    }

    @Test
    void prefixesTheKeyWithTheTenantsEnvironment() {
        var key = AccessFixtures.issue(access, admin(Environment.TEST), "Checkout", Set.of(CHARGES_READ));

        assertThat(key.secret()).startsWith("sk_test_");
    }

    @Test
    void refusesScopesTheIssuerDoesNotHave() {
        var developer = caller(Environment.TEST, Role.DEVELOPER);

        var result = access.issue(developer, "Refunds", Set.of(CHARGES_READ, REFUNDS_WRITE));

        assertThat(result).isEqualTo(new IssueResult.ScopesNotHeld(Set.of(REFUNDS_WRITE)));
        assertThat(keyCount(organization.test())).isZero();
    }

    @Test
    void refusesAKeyBeyondTheLimitUntilOneIsRevoked() {
        var admin = admin(Environment.TEST);
        var first = AccessFixtures.issue(access, admin, "Key 0", Set.of(CHARGES_READ));
        for (int i = 1; i < AccessApi.MAX_ACTIVE_KEYS; i++) {
            AccessFixtures.issue(access, admin, "Key " + i, Set.of(CHARGES_READ));
        }

        assertThat(access.issue(admin, "One too many", Set.of(CHARGES_READ)))
                .isEqualTo(new IssueResult.LimitReached(AccessApi.MAX_ACTIVE_KEYS));

        access.revoke(admin, first.id());
        assertThat(access.issue(admin, "Replacement", Set.of(CHARGES_READ))).isInstanceOf(IssueResult.Issued.class);
    }

    @Test
    void countsTheLimitPerTenant() {
        var test = admin(Environment.TEST);
        for (int i = 0; i < AccessApi.MAX_ACTIVE_KEYS; i++) {
            AccessFixtures.issue(access, test, "Key " + i, Set.of(CHARGES_READ));
        }

        assertThat(access.issue(admin(Environment.LIVE), "Live", Set.of(CHARGES_READ)))
                .isInstanceOf(IssueResult.Issued.class);
    }

    @Test
    void rejectsAHumanOnlyPermissionAsAScope() {
        assertThatThrownBy(() -> access.issue(admin(Environment.TEST), "Keys", Set.of(API_KEYS_MANAGE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not an API key scope: api_keys:manage");
    }

    @Test
    void rejectsAKeyWithoutScopes() {
        assertThatThrownBy(() -> access.issue(admin(Environment.TEST), "Checkout", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one scope");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> access.issue(admin(Environment.TEST), " ", Set.of(CHARGES_READ)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsANameLongerThanOneHundredCharacters() {
        var name = "a".repeat(AccessApi.MAX_NAME_LENGTH + 1);

        assertThatThrownBy(() -> access.issue(admin(Environment.TEST), name, Set.of(CHARGES_READ)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 100 characters");
    }

    private MemberCaller admin(Environment environment) {
        return AccessFixtures.admin(organization, environment);
    }

    private MemberCaller caller(Environment environment, Role role) {
        return AccessFixtures.member(organization, environment, organization.admin(), role);
    }

    private int keyCount(TenantId tenant) {
        return owner.sql("SELECT count(*) FROM access.api_keys WHERE tenant_id = ?")
                .param(tenant.value())
                .query(Integer.class)
                .single();
    }
}
