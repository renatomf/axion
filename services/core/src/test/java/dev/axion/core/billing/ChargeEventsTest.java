package dev.axion.core.billing;

import static dev.axion.core.billing.BillingFixtures.brl;
import static dev.axion.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.events.CommittedEvents;
import dev.axion.core.rails.PixCredit;
import dev.axion.core.rails.PixCreditHandler;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.Money;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The events billing publishes (events and webhooks design, section 3.3), each in the transaction of
 * its change: received once it commits, recorded in the publication registry, and never published
 * by a change that rolls back.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, CommittedEvents.class, ChargeEventsTest.Configuration.class})
class ChargeEventsTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-22T14:30:00Z"));
        }
    }

    private static final Actor SETTLEMENT = new Actor.Job("pix-settlement");

    private final BillingApi billing;
    private final PixCreditHandler settlement;
    private final MutableClock clock;
    private final TenancyApi tenancy;
    private final CommittedEvents events;
    private final TransactionTemplate transaction;

    private NewOrganization organization;

    @Autowired
    ChargeEventsTest(
            BillingApi billing,
            PixCreditHandler settlement,
            MutableClock clock,
            TenancyApi tenancy,
            CommittedEvents events,
            PlatformTransactionManager transactions) {
        this.billing = billing;
        this.settlement = settlement;
        this.clock = clock;
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

    @Test
    void publishesThePaymentThatSettlesTheCharge() {
        ChargeView charge = create(ChargeRequest.of(brl(10_000)));
        PixCredit credit = credit(charge, 10_000, clock.instant());

        settlement.handle(credit);

        assertThat(events.of(organization.test(), ChargePaid.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.charge()).isEqualTo(read(charge));
                    assertThat(event.charge().charge().status()).isEqualTo(ChargeStatus.PAID);
                    assertHeadedBySettlement(event.header(), credit);
                });
        assertThat(events.publications(organization.test()))
                .containsExactly(ChargeCreated.class.getName(), ChargePaid.class.getName());
    }

    @Test
    void publishesAFlaggedPaymentWithItsReason() {
        ChargeView charge = create(ChargeRequest.of(brl(10_000)));
        PixCredit credit = credit(charge, 9_999, clock.instant());

        settlement.handle(credit);

        assertThat(events.of(organization.test(), PaymentFlagged.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.payment().endToEndId()).isEqualTo(credit.endToEndId());
                    assertThat(event.payment().reviewReason()).contains(ReviewReason.AMOUNT_MISMATCH);
                    assertThat(event.charge()).isEqualTo(read(charge));
                    assertThat(event.charge().payments()).containsExactly(event.payment());
                    assertHeadedBySettlement(event.header(), credit);
                });
        assertThat(events.of(organization.test(), ChargePaid.class)).isEmpty();
    }

    @Test
    void publishesTheExpiryOfADueChargeBeforeTheLatePaymentThatFoundIt() {
        ChargeView charge = create(new ChargeRequest(brl(10_000), null, Duration.ofMinutes(30), Map.of()));
        clock.advance(Duration.ofMinutes(31));
        PixCredit credit = credit(charge, 10_000, clock.instant());

        settlement.handle(credit);

        assertThat(events.publications(organization.test()))
                .containsExactlyInAnyOrder(
                        ChargeCreated.class.getName(), ChargeExpired.class.getName(), PaymentFlagged.class.getName());
        assertThat(events.types(organization.test()))
                .containsExactly(
                        ChargeCreated.class.getName(), ChargeExpired.class.getName(), PaymentFlagged.class.getName());
        assertThat(events.of(organization.test(), ChargeExpired.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.charge().charge().status()).isEqualTo(ChargeStatus.EXPIRED);
                    assertHeadedBySettlement(event.header(), credit);
                });
        assertThat(events.of(organization.test(), PaymentFlagged.class))
                .singleElement()
                .satisfies(event -> assertThat(event.payment().reviewReason()).contains(ReviewReason.CHARGE_EXPIRED));
    }

    @Test
    void publishesNothingForACreditAlreadyRecorded() {
        ChargeView charge = create(ChargeRequest.of(brl(10_000)));
        PixCredit credit = credit(charge, 10_000, clock.instant());
        settlement.handle(credit);

        settlement.handle(credit);

        assertThat(events.of(organization.test(), ChargePaid.class)).hasSize(1);
    }

    @Test
    void publishesARefundWithTheChargeItLeft() {
        ChargeView charge = create(ChargeRequest.of(brl(10_000)));
        settlement.handle(credit(charge, 10_000, clock.instant()));

        RefundView refund = refund(charge, Optional.of(brl(4_000)));

        assertThat(events.of(organization.test(), ChargeRefunded.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.refund()).isEqualTo(refund);
                    assertThat(event.charge()).isEqualTo(read(charge));
                    assertThat(event.charge().charge().status()).isEqualTo(ChargeStatus.PARTIALLY_REFUNDED);
                    assertHeadedByTheCaller(event.header());
                });
    }

    @Test
    void publishesNothingForARefusedRefund() {
        ChargeView charge = create(ChargeRequest.of(brl(10_000)));

        RefundResult result = TestCaller.call(
                organization.test(),
                Environment.TEST,
                () -> billing.refund(
                        charge.id(), new RefundRequest(Optional.empty(), Optional.empty(), TestCaller.CORRELATION_ID)));

        assertThat(result).isInstanceOf(RefundResult.Refused.class);
        assertThat(events.of(organization.test(), ChargeRefunded.class)).isEmpty();
    }

    private ChargeView create(ChargeRequest request) {
        return TestCaller.call(organization.test(), Environment.TEST, () -> created(billing.createCharge(request)));
    }

    private RefundView refund(ChargeView charge, Optional<Money> amount) {
        RefundResult result = TestCaller.call(
                organization.test(),
                Environment.TEST,
                () -> billing.refund(
                        charge.id(), new RefundRequest(Optional.empty(), amount, TestCaller.CORRELATION_ID)));
        return ((RefundResult.Refunded) result).refund();
    }

    private ChargeDetail read(ChargeView charge) {
        return TestCaller.call(organization.test(), Environment.TEST, () -> billing.charge(charge.id()))
                .orElseThrow();
    }

    private static PixCredit credit(ChargeView charge, long minorUnits, Instant acceptedAt) {
        return new PixCredit(
                BillingFixtures.endToEndId(),
                "M99990011" + UUID.randomUUID().toString().replace("-", "").substring(0, 23),
                charge.transactionId(),
                brl(minorUnits),
                acceptedAt,
                "99990011");
    }

    private void assertHeadedBySettlement(EventHeader header, PixCredit credit) {
        assertThat(header.tenant()).isEqualTo(organization.test());
        assertThat(header.environment()).isEqualTo(Environment.TEST);
        assertThat(header.actor()).isEqualTo(SETTLEMENT);
        assertThat(header.correlationId()).isEqualTo(credit.messageId());
        assertThat(header.occurredAt()).isEqualTo(clock.instant());
    }

    private void assertHeadedByTheCaller(EventHeader header) {
        assertThat(header.tenant()).isEqualTo(organization.test());
        assertThat(header.environment()).isEqualTo(Environment.TEST);
        assertThat(header.actor()).isEqualTo(TestCaller.ACTOR);
        assertThat(header.correlationId()).isEqualTo(TestCaller.CORRELATION_ID);
    }
}
