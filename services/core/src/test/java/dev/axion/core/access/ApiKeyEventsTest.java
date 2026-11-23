package dev.axion.core.access;

import static dev.axion.core.tenancy.Permission.CHARGES_READ;
import static dev.axion.core.tenancy.Permission.CHARGES_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.events.CommittedEvents;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.MemberId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Role;
import dev.axion.core.tenancy.TenancyApi;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The events of API keys (events and webhooks design, section 3.3), each published in the
 * transaction of its change with the member who made it.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, CommittedEvents.class})
class ApiKeyEventsTest {

    private final AccessApi access;
    private final TenancyApi tenancy;
    private final CommittedEvents events;

    private NewOrganization organization;
    private MemberCaller admin;

    @Autowired
    ApiKeyEventsTest(AccessApi access, TenancyApi tenancy, CommittedEvents events) {
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.events = events;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
        admin = AccessFixtures.admin(organization, Environment.TEST);
    }

    @Test
    void publishesAnIssuedKeyWithoutItsSecret() {
        IssuedApiKey key = AccessFixtures.issue(access, admin, "Checkout", Set.of(CHARGES_READ));

        assertThat(events.of(organization.test(), ApiKeyIssued.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.key())
                            .isEqualTo(access.keys(organization.test()).getFirst());
                    assertThat(event.key().id()).isEqualTo(key.id());
                    assertHeadedByTheAdmin(event.header());
                });
    }

    @Test
    void publishesNothingForAKeyNotIssued() {
        MemberCaller viewer =
                AccessFixtures.member(organization, Environment.TEST, new MemberId(UUID.randomUUID()), Role.READ_ONLY);

        IssueResult result = access.issue(viewer, "Checkout", Set.of(CHARGES_WRITE));

        assertThat(result).isInstanceOf(IssueResult.ScopesNotHeld.class);
        assertThat(events.publications(organization.test())).isEmpty();
    }

    @Test
    void publishesARollWithTheReplacementAndTheKeyItReplaces() {
        IssuedApiKey key = AccessFixtures.issue(access, admin, "Checkout", Set.of(CHARGES_READ));

        var rolled = (RollResult.Rolled) access.roll(admin, key.id(), Duration.ofHours(1));

        assertThat(events.of(organization.test(), ApiKeyRolled.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.replacement().id())
                            .isEqualTo(rolled.replacement().id());
                    assertThat(event.rolled()).isEqualTo(rolled.rolled());
                    assertThat(event.rolled().expiresAt()).isPresent();
                    assertHeadedByTheAdmin(event.header());
                });
    }

    @Test
    void publishesARevocationOnce() {
        IssuedApiKey key = AccessFixtures.issue(access, admin, "Checkout", Set.of(CHARGES_READ));
        ApiKeyView revoked = access.revoke(admin, key.id()).orElseThrow();

        access.revoke(admin, key.id());

        assertThat(events.of(organization.test(), ApiKeyRevoked.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.key()).isEqualTo(revoked);
                    assertHeadedByTheAdmin(event.header());
                });
        assertThat(events.publications(organization.test()))
                .containsExactly(ApiKeyIssued.class.getName(), ApiKeyRevoked.class.getName());
    }

    private void assertHeadedByTheAdmin(EventHeader header) {
        assertThat(header.tenant()).isEqualTo(organization.test());
        assertThat(header.environment()).isEqualTo(Environment.TEST);
        assertThat(header.actor()).isEqualTo(new Actor.Member(organization.admin()));
        assertThat(header.correlationId()).isEqualTo(TestCaller.CORRELATION_ID);
    }
}
