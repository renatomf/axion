package dev.finaxis.core.billing;

import static dev.finaxis.core.billing.BillingFixtures.brl;
import static dev.finaxis.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.rails.PixCredit;
import dev.finaxis.core.rails.PixCreditHandler;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final PixCreditHandler settlement;

    private NewOrganization organization;

    @Autowired
    ReadChargeTest(BillingApi billing, TenancyApi tenancy, PixCreditHandler settlement) {
        this.billing = billing;
        this.tenancy = tenancy;
        this.settlement = settlement;
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
    void readsNoPaymentOrRefundOfAChargeNotPaidYet() {
        var created = create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));

        ChargeDetail detail =
                detail(organization.test(), Environment.TEST, created.id()).orElseThrow();

        assertThat(detail.payments()).isEmpty();
        assertThat(detail.refunds()).isEmpty();
    }

    @Test
    void readsThePaymentsTheChargeReceivedOldestFirst() {
        var created = create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));
        PixCredit mismatched = credit(created, 5_000, created.createdAt().plusSeconds(1));
        PixCredit settling = credit(created, 10_000, created.createdAt().plusSeconds(2));
        settlement.handle(mismatched);
        settlement.handle(settling);

        ChargeDetail detail =
                detail(organization.test(), Environment.TEST, created.id()).orElseThrow();

        assertThat(detail.charge().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(detail.payments())
                .extracting(
                        PaymentView::endToEndId,
                        PaymentView::amount,
                        PaymentView::amountRefunded,
                        PaymentView::acceptedAt,
                        PaymentView::outcome,
                        PaymentView::reviewReason)
                .containsExactly(
                        tuple(
                                mismatched.endToEndId(),
                                brl(5_000),
                                brl(0),
                                mismatched.acceptedAt(),
                                PaymentOutcome.FLAGGED,
                                Optional.of(ReviewReason.AMOUNT_MISMATCH)),
                        tuple(
                                settling.endToEndId(),
                                brl(10_000),
                                brl(0),
                                settling.acceptedAt(),
                                PaymentOutcome.SETTLED,
                                Optional.empty()));
    }

    @Test
    void readsTheRefundsMadeOfTheChargeOldestFirst() {
        var created = create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));
        settlement.handle(credit(created, 10_000, created.createdAt().plusSeconds(1)));
        RefundView first = refund(created, 2_500);
        RefundView second = refund(created, 1_500);

        ChargeDetail detail =
                detail(organization.test(), Environment.TEST, created.id()).orElseThrow();

        assertThat(detail.refunds()).containsExactly(first, second);
        assertThat(detail.charge().amountRefunded()).isEqualTo(brl(4_000));
        assertThat(detail.payments().getFirst().amountRefunded()).isEqualTo(brl(4_000));
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
        return detail(tenant, environment, charge).map(ChargeDetail::charge);
    }

    private Optional<ChargeDetail> detail(TenantId tenant, Environment environment, ChargeId charge) {
        return TenantContext.call(tenant, environment, () -> billing.charge(charge));
    }

    private RefundView refund(ChargeView charge, long minorUnits) {
        var request = new RefundRequest(Optional.empty(), Optional.of(brl(minorUnits)), "req-" + UUID.randomUUID());
        RefundResult result =
                TenantContext.call(organization.test(), Environment.TEST, () -> billing.refund(charge.id(), request));
        return ((RefundResult.Refunded) result).refund();
    }

    private PixCredit credit(ChargeView charge, long minorUnits, Instant acceptedAt) {
        return new PixCredit(
                BillingFixtures.endToEndId(),
                "M99990011aB3dE5gH7jK2mN4pQ6rS8tU",
                charge.transactionId(),
                brl(minorUnits),
                acceptedAt,
                "99990011");
    }
}
