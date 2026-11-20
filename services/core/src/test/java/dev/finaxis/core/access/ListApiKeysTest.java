package dev.finaxis.core.access;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static dev.finaxis.core.tenancy.Permission.CHARGES_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * {@link AccessApi#keys} against a real PostgreSQL. Each test creates its own organization, so it
 * never meets another test's keys.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ListApiKeysTest {

    private static final Instant MOMENT = Instant.parse("2026-11-19T12:00:00Z");

    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient owner;
    private NewOrganization organization;

    @Autowired
    ListApiKeysTest(AccessApi access, TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.access = new TenantBoundAccess(access);
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void showsAnIssuedKeyAsItWasIssued() {
        var key = issued(Environment.TEST, "Checkout", Set.of(CHARGES_READ, CHARGES_WRITE));

        assertThat(access.keys(organization.test()))
                .containsExactly(new ApiKeyView(
                        key.id(),
                        "Checkout",
                        key.lastFour(),
                        Set.of(CHARGES_READ, CHARGES_WRITE),
                        key.createdAt(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void listsOnlyTheTenantsKeysNewestFirst() {
        var first = issued(Environment.TEST, "First", Set.of(CHARGES_READ));
        var second = issued(Environment.TEST, "Second", Set.of(CHARGES_READ));
        issued(Environment.LIVE, "Live", Set.of(CHARGES_READ));

        assertThat(access.keys(organization.test()))
                .extracting(ApiKeyView::id)
                .containsExactly(second.id(), first.id());
    }

    @Test
    void showsWhatHappenedToAKey() {
        var key = issued(Environment.TEST, "Checkout", Set.of(CHARGES_READ));
        owner.sql("UPDATE access.api_keys SET expires_at = ?, revoked_at = ?, last_used_at = ? WHERE id = ?")
                .params(
                        utc(MOMENT.plusSeconds(2)),
                        utc(MOMENT.plusSeconds(3)),
                        utc(MOMENT.plusSeconds(1)),
                        key.id().value())
                .update();

        var view = access.keys(organization.test()).getFirst();

        assertThat(view.expiresAt()).contains(MOMENT.plusSeconds(2));
        assertThat(view.revokedAt()).contains(MOMENT.plusSeconds(3));
        assertThat(view.lastUsedAt()).contains(MOMENT.plusSeconds(1));
    }

    @Test
    void listsNothingForATenantWithoutKeys() {
        assertThat(access.keys(organization.live())).isEmpty();
    }

    private IssuedApiKey issued(Environment environment, String name, Set<Permission> scopes) {
        return AccessFixtures.issue(access, AccessFixtures.admin(organization, environment), name, scopes);
    }

    private static Object utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
