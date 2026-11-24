package dev.axion.core.notifications.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.axion.core.KeycloakTestConfiguration;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.billing.BillingApi;
import dev.axion.core.billing.BillingFixtures;
import dev.axion.core.billing.ChargeRequest;
import dev.axion.core.billing.ChargeView;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The live stream on {@code /internal} (events and webhooks design, section 10). Tokens skip
 * decoding: real ones are proven in {@code TenantResolutionTest}. Another tenant's stream is refused
 * in {@code TenantIsolationTest}.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KeycloakTestConfiguration.class})
@AutoConfigureMockMvc
class EventStreamControllerTest {

    private static final String PATH = "/internal/tenants/{tenant_id}/events/stream";

    private final MockMvc mvc;
    private final TenancyApi tenancy;
    private final BillingApi billing;

    private String subject = "";
    private NewOrganization organization;

    @Autowired
    EventStreamControllerTest(MockMvc mvc, TenancyApi tenancy, BillingApi billing) {
        this.mvc = mvc;
        this.tenancy = tenancy;
        this.billing = billing;
    }

    @BeforeEach
    void createOrganization() {
        subject = UUID.randomUUID().toString();
        organization = tenancy.createOrganization("Acme", subject);
    }

    @Test
    void sendsAMemberTheirTenantsEventsWithinASecond() throws Exception {
        MvcResult stream = open(organization.test());

        ChargeView charge = createCharge(organization.test());

        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
        assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        await().atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(content(stream.getResponse()))
                        .contains("event:charge.created\n")
                        .containsPattern("(?m)^id:evt_[0-9a-z]{26}$")
                        .contains("\"subject\":\"" + charge.id().publicId() + "\""));
    }

    @Test
    void sendsNothingOfAnotherEnvironmentsTenant() throws Exception {
        MvcResult live = open(organization.live());

        ChargeView charge = createCharge(organization.test());

        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(content(live.getResponse()))
                        .doesNotContain(charge.id().publicId().toString()));
    }

    private MvcResult open(TenantId tenant) throws Exception {
        return mvc.perform(get(PATH, tenant.publicId())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andReturn();
    }

    private ChargeView createCharge(TenantId tenant) {
        Environment environment = tenant.equals(organization.test()) ? Environment.TEST : Environment.LIVE;
        return TestCaller.call(
                tenant,
                environment,
                () -> BillingFixtures.created(billing.createCharge(ChargeRequest.of(BillingFixtures.brl(10_000)))));
    }

    private static String content(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString();
    }
}
