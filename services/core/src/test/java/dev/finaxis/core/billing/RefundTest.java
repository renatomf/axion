package dev.finaxis.core.billing;

import static dev.finaxis.core.billing.BillingFixtures.brl;
import static dev.finaxis.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.MutableClock;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.billing.application.Refunds;
import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountRequest;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.EntryRequest;
import dev.finaxis.core.ledger.EntrySource;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.ledger.PostResult;
import dev.finaxis.core.ledger.PostingRequest;
import dev.finaxis.core.ledger.application.BalanceVerifier;
import dev.finaxis.core.ledger.application.LedgerVerification;
import dev.finaxis.core.rails.CreditResult;
import dev.finaxis.core.rails.PixCredit;
import dev.finaxis.core.rails.PixCreditHandler;
import dev.finaxis.core.shared.BusinessCalendar;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Refunds against a real PostgreSQL (billing design, section 10): full, partial and repeated
 * partial refunds post entries that reverse the payment's, each rule of section 10.2 refuses with
 * nothing written, and concurrent refunds of one payment never return more than it brought in. The
 * clock stands still until a test moves it.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, RefundTest.Configuration.class})
class RefundTest {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-22T14:30:00Z"));
        }
    }

    private final BillingApi billing;
    private final PixCreditHandler settlement;
    private final TenancyApi tenancy;
    private final LedgerApi ledger;
    private final BalanceVerifier verifier;
    private final JdbcClient owner;
    private final MutableClock clock;
    private final MeterRegistry meters;

    private NewOrganization organization;
    private ChargeView charge;

    @Autowired
    RefundTest(
            BillingApi billing,
            PixCreditHandler settlement,
            TenancyApi tenancy,
            LedgerApi ledger,
            BalanceVerifier verifier,
            DatabaseOwner owner,
            MutableClock clock,
            MeterRegistry meters) {
        this.billing = billing;
        this.settlement = settlement;
        this.tenancy = tenancy;
        this.ledger = ledger;
        this.verifier = verifier;
        this.owner = owner.jdbc();
        this.clock = clock;
        this.meters = meters;
    }

    /** A charge of R$ 100.00 of a new tenant, expiring in 30 minutes. */
    @BeforeEach
    void createCharge() {
        organization = tenancy.createOrganization("Padaria São João", "subject-" + UUID.randomUUID());
        charge = TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> created(
                        billing.createCharge(new ChargeRequest(brl(10_000), null, Duration.ofMinutes(30), Map.of()))));
    }

    /** What a test commits must leave the ledger's invariants intact (ledger design, section 8). */
    @AfterEach
    void leavesTheLedgerSound() {
        assertThat(verifier.verify()).isEqualTo(new LedgerVerification(List.of(), List.of(), List.of()));
    }

    @Test
    void refundsAllOfTheSettlingPaymentWhenNoAmountIsGiven() {
        PixCredit credit = pay(10_000);

        RefundView refund = refunded(refund(Optional.empty(), Optional.empty()));

        assertThat(refund.amount()).isEqualTo(brl(10_000));
        assertThat(refund.charge()).isEqualTo(charge.id());
        assertThat(refund.payment()).isEqualTo(paymentId(credit));
        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.createdAt()).isEqualTo(clock.instant());
        ChargeView after = read();
        assertThat(after.status()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(after.amountRefunded()).isEqualTo(brl(10_000));
        assertThat(after.paidAt()).isEqualTo(credit.acceptedAt());
        assertThat(payment(credit)).containsEntry("amount_refunded", 10_000L);
        assertThat(available()).isEqualTo(brl(0));
    }

    @Test
    void refundsPartOfTheSettlingPayment() {
        PixCredit credit = pay(10_000);

        refunded(refund(Optional.empty(), Optional.of(brl(2_500))));

        ChargeView after = read();
        assertThat(after.status()).isEqualTo(ChargeStatus.PARTIALLY_REFUNDED);
        assertThat(after.amountRefunded()).isEqualTo(brl(2_500));
        assertThat(payment(credit)).containsEntry("amount_refunded", 2_500L);
        assertThat(available()).isEqualTo(brl(7_500));
    }

    @Test
    void refundsTheRestOfAPartiallyRefundedPaymentInSeveralParts() {
        PixCredit credit = pay(10_000);

        refunded(refund(Optional.empty(), Optional.of(brl(2_500))));
        refunded(refund(Optional.empty(), Optional.of(brl(2_500))));
        RefundView last = refunded(refund(Optional.empty(), Optional.empty()));

        assertThat(last.amount()).isEqualTo(brl(5_000));
        assertThat(read().status()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(read().amountRefunded()).isEqualTo(brl(10_000));
        assertThat(payment(credit)).containsEntry("amount_refunded", 10_000L);
        assertThat(available()).isEqualTo(brl(0));
    }

    @Test
    void postsTheReverseOfThePaymentsEntryOnTheRefundsBusinessDate() {
        PixCredit credit = pay(10_000);

        RefundView refund = refunded(refund(Optional.empty(), Optional.of(brl(4_000)), "req-reversal"));

        Map<String, @Nullable Object> entry =
                owner.sql("""
                        SELECT id, tenant_id, effective_date, correlation_id, reverses_entry_id
                          FROM ledger.entries
                         WHERE source_type = 'refund' AND source_id = ? AND purpose = 'refund'
                        """).param(refund.id().value().toString()).query().singleRow();
        assertThat(entry)
                .containsEntry("tenant_id", organization.test().value())
                .containsEntry("effective_date", Date.valueOf(BusinessCalendar.businessDateOf(clock.instant())))
                .containsEntry("correlation_id", "req-reversal")
                .containsEntry("reverses_entry_id", payment(credit).get("entry_id"));
        assertThat(postings(entry.get("id")))
                .containsExactly(
                        "platform:settlement CREDIT 4000",
                        "tenant:" + organization.test().value() + ":available DEBIT 4000");
        assertThat(refundRow(refund))
                .containsEntry("tenant_id", organization.test().value())
                .containsEntry("charge_id", charge.id().value())
                .containsEntry("payment_id", refund.payment().value())
                .containsEntry("amount_minor", 4_000L)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("entry_id", entry.get("id"))
                .containsEntry("correlation_id", "req-reversal");
    }

    @Test
    void refundsAFlaggedPaymentWithoutChangingTheCharge() {
        pay(10_000);
        PixCredit flagged = pay(3_000);

        refunded(refund(Optional.of(paymentId(flagged)), Optional.empty()));

        ChargeView after = read();
        assertThat(after.status()).isEqualTo(ChargeStatus.PAID);
        assertThat(after.amountRefunded()).isEqualTo(brl(0));
        assertThat(payment(flagged)).containsEntry("amount_refunded", 3_000L);
        assertThat(available()).isEqualTo(brl(10_000));
    }

    @Test
    void refusesARefundOfAnUnknownCharge() {
        assertThat(TenantContext.call(
                        organization.test(),
                        Environment.TEST,
                        () -> billing.refund(
                                new ChargeId(UUID.randomUUID()), request(Optional.empty(), Optional.empty()))))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.CHARGE_NOT_FOUND));
    }

    @Test
    void refusesARefundOfAnotherTenantsCharge() {
        pay(10_000);
        NewOrganization other = tenancy.createOrganization("Mercado Bom Preço", "subject-" + UUID.randomUUID());

        assertThat(TenantContext.call(
                        other.test(),
                        Environment.TEST,
                        () -> billing.refund(charge.id(), request(Optional.empty(), Optional.empty()))))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.CHARGE_NOT_FOUND));
        assertNothingRefunded();
    }

    @Test
    void refusesARefundOfAChargeWithNoPayment() {
        assertThat(refund(Optional.empty(), Optional.empty()))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.CHARGE_NOT_PAID));
    }

    @Test
    void refusesARefundThatNamesNoPaymentOfAChargeWithSeveral() {
        pay(10_000);
        pay(10_000);

        assertThat(refund(Optional.empty(), Optional.empty()))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.PAYMENT_REQUIRED));
        assertNothingRefunded();
    }

    @Test
    void refusesARefundOfAPaymentOfAnotherCharge() {
        pay(10_000);

        assertThat(refund(Optional.of(new PaymentId(UUID.randomUUID())), Optional.empty()))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.UNKNOWN_PAYMENT));
        assertNothingRefunded();
    }

    @Test
    void refusesARefundOfMoreThanRemainsOfThePayment() {
        pay(10_000);
        refunded(refund(Optional.empty(), Optional.of(brl(6_000))));

        assertThat(refund(Optional.empty(), Optional.of(brl(4_001))))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.AMOUNT_EXCEEDS_REFUNDABLE));
        assertThat(read().amountRefunded()).isEqualTo(brl(6_000));
        assertThat(available()).isEqualTo(brl(4_000));
    }

    @Test
    void refusesARefundOfAFullyRefundedPayment() {
        pay(10_000);
        refunded(refund(Optional.empty(), Optional.empty()));

        assertThat(refund(Optional.empty(), Optional.empty()))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.AMOUNT_EXCEEDS_REFUNDABLE));
    }

    @Test
    void refusesARefundMoreThanNinetyDaysAfterThePaymentWasAccepted() {
        PixCredit credit = pay(10_000);
        clock.advance(Duration.between(clock.instant(), credit.acceptedAt().plus(Duration.ofDays(90))));
        refunded(refund(Optional.empty(), Optional.of(brl(1))));

        clock.advance(Duration.ofNanos(1_000));

        assertThat(refund(Optional.empty(), Optional.of(brl(1))))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.REFUND_WINDOW_CLOSED));
    }

    @Test
    void refusesARefundBeyondTheFiftiethOfACharge() {
        pay(10_000);
        for (int i = 0; i < Charge.MAX_REFUNDS; i++) {
            refunded(refund(Optional.empty(), Optional.of(brl(1))));
        }

        assertThat(refund(Optional.empty(), Optional.of(brl(1))))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.REFUND_LIMIT_REACHED));
        assertThat(read().amountRefunded()).isEqualTo(brl(Charge.MAX_REFUNDS));
    }

    @Test
    void refusesARefundTheTenantsBalanceCannotCover() {
        PixCredit credit = pay(10_000);
        hold(brl(9_000));

        assertThat(refund(Optional.empty(), Optional.of(brl(1_001))))
                .isEqualTo(new RefundResult.Refused(RefundRefusal.INSUFFICIENT_BALANCE));
        assertNothingRefunded();
        assertThat(read().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(payment(credit)).containsEntry("amount_refunded", 0L);
        assertThat(available()).isEqualTo(brl(1_000));
    }

    @Test
    void refundsAPaymentExactlyTenTimesWhenTwentyRefundsOfATenthRaceForIt() throws Exception {
        PixCredit credit = pay(10_000);

        List<RefundResult> results = atOnce(20, () -> refund(Optional.empty(), Optional.of(brl(1_000))));

        assertThat(results).filteredOn(RefundResult.Refunded.class::isInstance).hasSize(10);
        assertThat(results)
                .filteredOn(RefundResult.Refused.class::isInstance)
                .containsOnly(new RefundResult.Refused(RefundRefusal.AMOUNT_EXCEEDS_REFUNDABLE))
                .hasSize(10);
        assertThat(read().status()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(payment(credit)).containsEntry("amount_refunded", 10_000L);
        assertThat(available()).isEqualTo(brl(0));
    }

    @Test
    void countsTheRefundsMadeAndNotTheRefusedOnes() {
        pay(10_000);
        double before = refundsMade();

        refunded(refund(Optional.empty(), Optional.of(brl(2_500))));
        refund(Optional.empty(), Optional.of(brl(9_000)));

        assertThat(refundsMade()).isEqualTo(before + 1);
    }

    private double refundsMade() {
        return meters.get(Refunds.REFUNDS).counter().count();
    }

    private RefundResult refund(Optional<PaymentId> payment, Optional<Money> amount) {
        return refund(payment, amount, "req-" + UUID.randomUUID());
    }

    private RefundResult refund(Optional<PaymentId> payment, Optional<Money> amount, String correlationId) {
        return TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> billing.refund(charge.id(), new RefundRequest(payment, amount, correlationId)));
    }

    private static RefundRequest request(Optional<PaymentId> payment, Optional<Money> amount) {
        return new RefundRequest(payment, amount, "req-" + UUID.randomUUID());
    }

    private static RefundView refunded(RefundResult result) {
        return switch (result) {
            case RefundResult.Refunded refunded -> refunded.refund();
            case RefundResult.Refused refused -> throw new AssertionError("Refund refused: " + refused.reason());
        };
    }

    /** A credit of {@code minorUnits} for the charge, accepted ten minutes after it was created. */
    private PixCredit pay(long minorUnits) {
        var credit = new PixCredit(
                BillingFixtures.endToEndId(),
                "M99990011" + random(23),
                charge.transactionId(),
                brl(minorUnits),
                charge.createdAt().plusSeconds(600),
                "99990011");
        assertThat(settlement.handle(credit)).isEqualTo(CreditResult.RECORDED);
        return credit;
    }

    /** Moves part of the tenant's available balance to another of its accounts, as a payout's hold will. */
    private void hold(Money amount) {
        TenantContext.run(organization.test(), Environment.TEST, () -> {
            AccountId available = ledger.openAccount(availableAccount());
            AccountId held = ledger.openAccount(new AccountRequest(
                    organization.test().value(),
                    "tenant:" + organization.test().value() + ":held",
                    AccountType.LIABILITY,
                    Charge.CURRENCY,
                    false,
                    false));
            PostResult result = ledger.post(new EntryRequest(
                    organization.test().value(),
                    new EntrySource("test_hold", UUID.randomUUID().toString(), "hold"),
                    BusinessCalendar.businessDateOf(clock.instant()),
                    "req-hold",
                    List.of(PostingRequest.debit(available, amount), PostingRequest.credit(held, amount))));
            assertThat(result).isInstanceOf(PostResult.Posted.class);
        });
    }

    private ChargeView read() {
        return TenantContext.call(organization.test(), Environment.TEST, () -> billing.charge(charge.id()))
                .orElseThrow()
                .charge();
    }

    private AccountRequest availableAccount() {
        return new AccountRequest(
                organization.test().value(),
                "tenant:" + organization.test().value() + ":available",
                AccountType.LIABILITY,
                Charge.CURRENCY,
                false,
                false);
    }

    private Money available() {
        return TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> ledger.balanceOf(ledger.openAccount(availableAccount())).balance());
    }

    private PaymentId paymentId(PixCredit credit) {
        return new PaymentId(owner.sql("SELECT id FROM billing.payments WHERE end_to_end_id = ?")
                .param(credit.endToEndId())
                .query(UUID.class)
                .single());
    }

    private Map<String, @Nullable Object> payment(PixCredit credit) {
        return owner.sql("SELECT * FROM billing.payments WHERE end_to_end_id = ?")
                .param(credit.endToEndId())
                .query()
                .singleRow();
    }

    private Map<String, @Nullable Object> refundRow(RefundView refund) {
        return owner.sql("SELECT * FROM billing.refunds WHERE id = ?")
                .param(refund.id().value())
                .query()
                .singleRow();
    }

    private List<String> postings(@Nullable Object entry) {
        return owner.sql("""
                        SELECT a.code, p.direction, p.amount
                          FROM ledger.postings p JOIN ledger.accounts a ON a.id = p.account_id
                         WHERE p.entry_id = ?
                         ORDER BY p.direction, a.code
                        """)
                .param(entry)
                .query((row, rowNumber) ->
                        row.getString("code") + " " + row.getString("direction") + " " + row.getLong("amount"))
                .list();
    }

    private void assertNothingRefunded() {
        assertThat(owner.sql("SELECT count(*) FROM billing.refunds WHERE charge_id = ?")
                        .param(charge.id().value())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(owner.sql("""
                        SELECT count(*) FROM ledger.entries
                         WHERE tenant_id = ? AND source_type = 'refund'
                        """)
                        .param(organization.test().value())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** Runs the work {@code times} times together, from a common start, and waits for all. */
    private static <T> List<T> atOnce(int times, Callable<T> work) throws Exception {
        var start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(times)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                futures.add(threads.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static String random(int length) {
        var random = new SecureRandom();
        var text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            text.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return text.toString();
    }
}
