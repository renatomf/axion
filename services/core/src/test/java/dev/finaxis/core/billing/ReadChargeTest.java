package dev.finaxis.core.billing;

import static dev.finaxis.core.billing.BillingFixtures.brl;
import static dev.finaxis.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** {@link BillingApi#charge} against a real PostgreSQL. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ReadChargeTest {

    private final BillingApi billing;
    private final TenancyApi tenancy;

    private NewOrganization organization;

    @Autowired
    ReadChargeTest(BillingApi billing, TenancyApi tenancy) {
        this.billing = billing;
        this.tenancy = tenancy;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Padaria São João", "subject-" + UUID.randomUUID());
    }

    @Test
    void readsTheChargeAsItWasCreated() {
        var created = create(
                organization.test(),
                Environment.TEST,
                new ChargeRequest(
                        brl(10_000),
                        "Mensalidade de novembro",
                        Duration.ofMinutes(30),
                        Map.of("order_id", "ord_8842", "customer_ref", "c-17")));

        assertThat(read(organization.test(), Environment.TEST, created.id())).contains(created);
    }

    @Test
    void readsAChargeWithNoDescriptionOrMetadata() {
        var created = create(organization.live(), Environment.LIVE, ChargeRequest.of(brl(100)));

        assertThat(read(organization.live(), Environment.LIVE, created.id())).contains(created);
    }

    @Test
    void findsNoChargeOfAnotherTenant() {
        var created = create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));
        var other = tenancy.createOrganization("Other", "subject-" + UUID.randomUUID());

        assertThat(read(other.test(), Environment.TEST, created.id())).isEmpty();
        // The organization's other environment is another tenant too.
        assertThat(read(organization.live(), Environment.LIVE, created.id())).isEmpty();
    }

    @Test
    void findsNoUnknownCharge() {
        var unknown = new ChargeId(new UuidV7Generator(Clock.systemUTC()).next());

        assertThat(read(organization.test(), Environment.TEST, unknown)).isEmpty();
    }

    @Test
    void refusesToReadAChargeWithNoTenantBound() {
        var created = create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));

        assertThatThrownBy(() -> billing.charge(created.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a tenant bound");
    }

    private ChargeView create(TenantId tenant, Environment environment, ChargeRequest request) {
        return TenantContext.call(tenant, environment, () -> created(billing.createCharge(request)));
    }

    private Optional<ChargeView> read(TenantId tenant, Environment environment, ChargeId charge) {
        return TenantContext.call(tenant, environment, () -> billing.charge(charge));
    }
}
