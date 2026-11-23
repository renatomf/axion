package dev.axion.core.billing;

import static dev.axion.core.billing.BillingFixtures.brl;
import static dev.axion.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.events.CommittedEvents;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The events billing publishes (events and webhooks design, section 3.3), each in the transaction of
 * its change: received once it commits, recorded in the publication registry, and never published
 * by a change that rolls back.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, CommittedEvents.class})
class ChargeEventsTest {

    private final BillingApi billing;
    private final TenancyApi tenancy;
    private final CommittedEvents events;
    private final TransactionTemplate transaction;

    private NewOrganization organization;

    @Autowired
    ChargeEventsTest(
            BillingApi billing, TenancyApi tenancy, CommittedEvents events, PlatformTransactionManager transactions) {
        this.billing = billing;
        this.tenancy = tenancy;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Padaria São João", "subject-" + UUID.randomUUID());
    }

    @Test
    void publishesTheNewChargeWithItsCaller() {
        ChargeView charge = create(ChargeRequest.of(brl(10_000)));

        assertThat(events.of(organization.test(), ChargeCreated.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.charge()).isEqualTo(new ChargeDetail(charge, List.of(), List.of()));
                    assertHeadedByTheCaller(event.header());
                    assertThat(event.occurredAt()).isEqualTo(charge.createdAt());
                });
        assertThat(events.publications(organization.test())).containsExactly(ChargeCreated.class.getName());
    }

    @Test
    void publishesNothingWhenTheCallersTransactionRollsBack() {
        TestCaller.run(
                organization.test(),
                Environment.TEST,
                () -> transaction.executeWithoutResult(status -> {
                    billing.createCharge(ChargeRequest.of(brl(10_000)));
                    status.setRollbackOnly();
                }));

        assertThat(events.of(organization.test(), ChargeCreated.class)).isEmpty();
        assertThat(events.publications(organization.test())).isEmpty();
    }

    @Test
    void publishesNothingForAChargeRiskDenies() {
        TestCaller.call(
                organization.test(), Environment.TEST, () -> billing.createCharge(ChargeRequest.of(brl(100_001))));

        assertThat(events.publications(organization.test())).isEmpty();
    }

    private ChargeView create(ChargeRequest request) {
        return TestCaller.call(organization.test(), Environment.TEST, () -> created(billing.createCharge(request)));
    }

    private void assertHeadedByTheCaller(EventHeader header) {
        assertThat(header.tenant()).isEqualTo(organization.test());
        assertThat(header.environment()).isEqualTo(Environment.TEST);
        assertThat(header.actor()).isEqualTo(TestCaller.ACTOR);
        assertThat(header.correlationId()).isEqualTo(TestCaller.CORRELATION_ID);
    }
}
