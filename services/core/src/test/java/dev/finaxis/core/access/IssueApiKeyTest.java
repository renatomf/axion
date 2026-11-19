package dev.finaxis.core.access;

import static dev.finaxis.core.tenancy.Permission.API_KEYS_MANAGE;
import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static dev.finaxis.core.tenancy.Permission.CHARGES_WRITE;
import static dev.finaxis.core.tenancy.Permission.REFUNDS_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.domain.ApiKeySecret;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.Role;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.OffsetDateTime;
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
 * {@link AccessApi#issue} against a real PostgreSQL. Each test creates its own organization, so it
 * never meets another test's keys.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class IssueApiKeyTest {

    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient jdbc;
    private NewOrganization organization;

    @Autowired
    IssueApiKeyTest(AccessApi access, TenancyApi tenancy, JdbcClient jdbc) {
        this.access = access;
        this.tenancy = tenancy;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void storesOnlyTheHashOfTheSecretItReturns() {
        var key = issued(admin(Environment.LIVE), "Checkout", Set.of(CHARGES_READ, CHARGES_WRITE));

        var secret = ApiKeySecret.parse(key.secret()).orElseThrow();
        var stored = jdbc.sql("""
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
        var key = issued(admin(Environment.TEST), "Checkout", Set.of(CHARGES_READ));

        var stored = jdbc.sql("SELECT created_at FROM access.api_keys WHERE id = ?")
                .param(key.id().value())
                .query(OffsetDateTime.class)
                .single();
        assertThat(stored.toInstant()).isEqualTo(key.createdAt());
    }

    @Test
    void prefixesTheKeyWithTheTenantsEnvironment() {
        var key = issued(admin(Environment.TEST), "Checkout", Set.of(CHARGES_READ));

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
        var first = issued(admin, "Key 0", Set.of(CHARGES_READ));
        for (int i = 1; i < AccessApi.MAX_ACTIVE_KEYS; i++) {
            issued(admin, "Key " + i, Set.of(CHARGES_READ));
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
            issued(test, "Key " + i, Set.of(CHARGES_READ));
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

    /**
     * Acting as {@code finaxis_access} proves the code needs no privilege beyond the role's grants
     * (migration {@code V10}). The test's transaction is rolled back, so it leaves no rows behind.
     */
    @Test
    @Transactional
    void needsNoMoreThanTheAccessRolesGrants() {
        jdbc.sql("SET LOCAL ROLE finaxis_access").update();

        var result = access.issue(admin(Environment.TEST), "Checkout", Set.of(CHARGES_READ));

        assertThat(result).isInstanceOf(IssueResult.Issued.class);
    }

    private MemberCaller admin(Environment environment) {
        return caller(environment, Role.ADMIN);
    }

    /** The caller tenant resolution would build for the organization's admin, with these roles. */
    private MemberCaller caller(Environment environment, Role role) {
        TenantId tenant = environment == Environment.TEST ? organization.test() : organization.live();
        Set<Permission> permissions = Role.permissionsOf(Set.of(role));
        return new MemberCaller(tenant, environment, organization.admin(), permissions);
    }

    private IssuedApiKey issued(MemberCaller issuer, String name, Set<Permission> scopes) {
        var result = access.issue(issuer, name, scopes);
        assertThat(result).isInstanceOf(IssueResult.Issued.class);
        return ((IssueResult.Issued) result).key();
    }

    private int keyCount(TenantId tenant) {
        return jdbc.sql("SELECT count(*) FROM access.api_keys WHERE tenant_id = ?")
                .param(tenant.value())
                .query(Integer.class)
                .single();
    }
}
