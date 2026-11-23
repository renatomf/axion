package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.events.CommittedEvents;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.domain.UrlRefusal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link WebhooksApi#create} (events and webhooks design, sections 6.1 and 6.3), against a real PostgreSQL. */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class, CommittedEvents.class})
class CreateEndpointTest {

    private static final String URL = "https://orders.example.com/axion/webhooks";

    private final WebhooksApi webhooks;
    private final JdbcClient owner;
    private final CommittedEvents events;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    CreateEndpointTest(WebhooksApi webhooks, DatabaseOwner owner, CommittedEvents events) {
        this.webhooks = webhooks;
        this.owner = owner.jdbc();
        this.events = events;
    }

    @Test
    void registersAnEnabledEndpointWithANewSecret() {
        var request = new EndpointRequest(URL, Optional.of("Order service"), Set.of("charge.paid", "charge.refunded"));

        CreatedEndpoint created = created(create(request));

        EndpointView endpoint = created.endpoint();
        assertThat(endpoint.url()).isEqualTo(URL);
        assertThat(endpoint.description()).contains("Order service");
        assertThat(endpoint.enabledEvents()).containsExactlyInAnyOrder("charge.paid", "charge.refunded");
        assertThat(endpoint.status()).isEqualTo(EndpointStatus.ENABLED);
        assertThat(endpoint.disabledReason()).isEmpty();
        assertThat(created.secret()).matches("whsec_[0-9A-Za-z+/]{43}=");
        assertThat(owner.sql("""
                        SELECT url, description, enabled_events::text, status, disabled_reason IS NULL
                          FROM webhooks.endpoints
                         WHERE id = ? AND tenant_id = ?
                        """)
                        .param(endpoint.id().value())
                        .param(tenant.value())
                        .query((row, number) -> List.of(
                                row.getString(1),
                                row.getString(2),
                                row.getString(3),
                                row.getString(4),
                                row.getBoolean(5)))
                        .single())
                .containsExactly(URL, "Order service", "{charge.paid,charge.refunded}", "ENABLED", true);
        assertThat(secrets(endpoint.id())).isOne();
    }

    @Test
    void registersAnEndpointForEveryType() {
        CreatedEndpoint created = created(create(new EndpointRequest(URL, Optional.empty(), Set.of("*"))));

        assertThat(created.endpoint().enabledEvents()).containsExactly("*");
        assertThat(created.endpoint().description()).isEmpty();
    }

    @Test
    void givesEachEndpointItsOwnSecret() {
        CreatedEndpoint first = created(create(request()));
        CreatedEndpoint second = created(create(request()));

        assertThat(first.secret()).isNotEqualTo(second.secret());
    }

    @Test
    void leavesTheSecretOutOfTheEndpointsString() {
        CreatedEndpoint created = created(create(request()));

        assertThat(created.toString()).doesNotContain(created.secret().substring("whsec_".length()));
    }

    @Test
    void refusesAUrlToAPrivateAddressAndStoresNothing() {
        CreateEndpointResult result = create(new EndpointRequest(
                "https://hooks." + TestDns.PRIVATE + "/axion", Optional.empty(), Set.of("charge.paid")));

        assertThat(result).isEqualTo(new CreateEndpointResult.InvalidUrl(UrlRefusal.MUST_BE_PUBLIC));
        assertThat(endpoints()).isZero();
    }

    @Test
    void refusesAUrlThatDoesNotResolve() {
        CreateEndpointResult result =
                create(new EndpointRequest("https://" + TestDns.UNRESOLVABLE, Optional.empty(), Set.of("charge.paid")));

        assertThat(result).isEqualTo(new CreateEndpointResult.InvalidUrl(UrlRefusal.MUST_RESOLVE));
    }

    @Test
    void refusesAUrlThatIsNotHttps() {
        CreateEndpointResult result =
                create(new EndpointRequest("http://orders.example.com", Optional.empty(), Set.of("charge.paid")));

        assertThat(result).isEqualTo(new CreateEndpointResult.InvalidUrl(UrlRefusal.MUST_BE_HTTPS));
    }

    @Test
    void refusesTheSeventeenthEndpoint() {
        for (int i = 0; i < WebhooksApi.MAX_ENDPOINTS; i++) {
            created(create(request()));
        }

        assertThat(create(request())).isEqualTo(new CreateEndpointResult.LimitReached(WebhooksApi.MAX_ENDPOINTS));
        assertThat(endpoints()).isEqualTo(WebhooksApi.MAX_ENDPOINTS);
    }

    @Test
    void countsOnlyTheTenantsOwnEndpointsTowardItsLimit() {
        var other = new TenantId(UUID.randomUUID());
        for (int i = 0; i < WebhooksApi.MAX_ENDPOINTS; i++) {
            TestCaller.call(other, Environment.TEST, () -> created(webhooks.create(request())));
        }

        assertThat(create(request())).isInstanceOf(CreateEndpointResult.Created.class);
    }

    @Test
    void publishesTheEndpointWithoutItsSecret() {
        CreatedEndpoint created = created(create(request()));

        assertThat(events.of(tenant, WebhookEndpointCreated.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.endpoint()).isEqualTo(created.endpoint());
                    assertThat(event.header().tenant()).isEqualTo(tenant);
                    assertThat(event.header().actor()).isEqualTo(TestCaller.ACTOR);
                    assertThat(event.header().correlationId()).isEqualTo(TestCaller.CORRELATION_ID);
                    assertThat(event.toString()).doesNotContain(created.secret().substring("whsec_".length()));
                });
    }

    @Test
    void publishesNothingForARefusedUrl() {
        create(new EndpointRequest("http://orders.example.com", Optional.empty(), Set.of("charge.paid")));

        assertThat(events.publications(tenant)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"charge.unknown", "webhook_endpoint.disabled", "api_key.issued"})
    void refusesATypeEndpointsCannotReceive(String type) {
        assertThatThrownBy(() -> create(new EndpointRequest(URL, Optional.empty(), Set.of("charge.paid", type))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesTheWildcardBesideATypeAndNoTypeAtAll() {
        assertThatThrownBy(() -> create(new EndpointRequest(URL, Optional.empty(), Set.of("*", "charge.paid"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(new EndpointRequest(URL, Optional.empty(), Set.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesADescriptionOutsideItsLimits() {
        assertThatThrownBy(() -> create(new EndpointRequest(URL, Optional.of(""), Set.of("charge.paid"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(new EndpointRequest(URL, Optional.of("d".repeat(141)), Set.of("charge.paid"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsADescriptionInCodePoints() {
        assertThat(create(new EndpointRequest(URL, Optional.of("🔔".repeat(140)), Set.of("charge.paid"))))
                .isInstanceOf(CreateEndpointResult.Created.class);
    }

    @Test
    void refusesAUrlLongerThan2048Characters() {
        String url = "https://orders.example.com/" + "a".repeat(2_048);

        assertThatThrownBy(() -> create(new EndpointRequest(url, Optional.empty(), Set.of("charge.paid"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void needsATenantBound() {
        assertThatThrownBy(() -> webhooks.create(request())).isInstanceOf(IllegalStateException.class);
    }

    private CreateEndpointResult create(EndpointRequest request) {
        return TestCaller.call(tenant, Environment.TEST, () -> webhooks.create(request));
    }

    private static CreatedEndpoint created(CreateEndpointResult result) {
        assertThat(result).isInstanceOf(CreateEndpointResult.Created.class);
        return ((CreateEndpointResult.Created) result).endpoint();
    }

    private static EndpointRequest request() {
        return new EndpointRequest(URL, Optional.empty(), Set.of("charge.paid"));
    }

    private int endpoints() {
        return owner.sql("SELECT count(*) FROM webhooks.endpoints WHERE tenant_id = ?")
                .param(tenant.value())
                .query(Integer.class)
                .single();
    }

    private int secrets(WebhookEndpointId endpoint) {
        return owner.sql("SELECT count(*) FROM webhooks.secrets WHERE endpoint_id = ? AND tenant_id = ?")
                .param(endpoint.value())
                .param(tenant.value())
                .query(Integer.class)
                .single();
    }
}
