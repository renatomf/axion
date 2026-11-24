package dev.axion.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.access.AccessApi;
import dev.axion.core.access.AccessFixtures;
import dev.axion.core.access.IssuedApiKey;
import dev.axion.core.audit.domain.AuditActor;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import java.time.Duration;
import java.util.List;
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
 * Audited events followed from the commit of their change to their record (events and webhooks
 * design, section 9.4), each with the actor that made the change, against a real PostgreSQL.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
class AuditedEventsTest {

    private final TenancyApi tenancy;
    private final JdbcClient owner;

    private NewOrganization organization;

    @Autowired
    AuditedEventsTest(TenancyApi tenancy, DatabaseOwner owner) {
        this.tenancy = tenancy;
        this.owner = owner.jdbc();
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", "subject-" + UUID.randomUUID());
    }

    @Test
    void recordsAnIssuedKeyWithTheMemberWhoIssuedIt(@Autowired AccessApi access) {
        var admin = AccessFixtures.admin(organization, Environment.TEST);

        IssuedApiKey key = AccessFixtures.issue(access, admin, "Checkout", Set.of(Permission.CHARGES_READ));

        assertRecordedEventually(
                organization.test(),
                "api_key.issued",
                key.id().publicId().toString(),
                AuditActor.of(new Actor.Member(organization.admin())));
    }

    @Test
    void recordsACreatedEndpointWithTheCallerWhoCreatedIt(@Autowired WebhooksApi webhooks) {
        var request = new EndpointRequest("https://orders.example.com/axion", Optional.empty(), Set.of("*"));

        var created = TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.create(request));

        WebhookEndpointId endpoint =
                ((CreateEndpointResult.Created) created).endpoint().endpoint().id();
        assertRecordedEventually(
                organization.test(),
                "webhook_endpoint.created",
                endpoint.publicId().toString(),
                AuditActor.of(TestCaller.ACTOR));
    }

    private void assertRecordedEventually(TenantId tenant, String action, String resource, AuditActor actor) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(actors(tenant, action, resource)).containsExactly(actor));
    }

    private List<AuditActor> actors(TenantId tenant, String action, String resource) {
        return owner.sql("""
                        SELECT actor_type, actor_id FROM audit.records
                         WHERE tenant_id = ? AND action = ? AND resource_id = ?
                        """)
                .param(tenant.value())
                .param(action)
                .param(resource)
                .query((row, number) ->
                        new AuditActor(AuditActor.Type.valueOf(row.getString("actor_type")), row.getString("actor_id")))
                .list();
    }
}
