package dev.axion.core.billing;

import static dev.axion.core.billing.BillingFixtures.brl;
import static dev.axion.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.billing.application.ChargeExpiry;
import dev.axion.core.billing.application.Settlement;
import dev.axion.core.billing.domain.Charge;
import dev.axion.core.ledger.AccountRequest;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.LedgerApi;
import dev.axion.core.ledger.application.BalanceVerifier;
import dev.axion.core.ledger.application.LedgerVerification;
import dev.axion.core.rails.CreditResult;
import dev.axion.core.rails.PixCredit;
import dev.axion.core.rails.PixCreditHandler;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import java.util.concurrent.TimeUnit;
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
 * Settlement of Pix credits against a real PostgreSQL (billing design, section 8): every row of the
 * outcome table posts one balanced entry and records its payment, an unknown transaction ID records
 * nothing, and a credit handled twice, or twice at once, has one effect. The clock stands still
 * until a test moves it.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, SettlementTest.Configuration.class})
class SettlementTest {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-22T14:30:00Z"));
        }
    }

    private final PixCreditHandler settlement;
    private final ChargeExpiry expiry;
    private final BillingApi billing;
    private final TenancyApi tenancy;
    private final LedgerApi ledger;
    private final BalanceVerifier verifier;
    private final JdbcClient owner;
    private final MutableClock clock;
    private final MeterRegistry meters;

    private NewOrganization organization;
    private ChargeView charge;

    @Autowired
    SettlementTest(
            PixCreditHandler settlement,
            ChargeExpiry expiry,
            BillingApi billing,
            TenancyApi tenancy,
            LedgerApi ledger,
            BalanceVerifier verifier,
            DatabaseOwner owner,
            MutableClock clock,
            MeterRegistry meters) {
        this.settlement = settlement;
        this.expiry = expiry;
        this.billing = billing;
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
    void settlesTheChargeWithAPaymentOfItsAmountAcceptedBeforeItsExpiry() {
        PixCredit credit = credit(10_000, charge.createdAt().plusSeconds(600));

        assertThat(settlement.handle(credit)).isEqualTo(CreditResult.RECORDED);

        ChargeView paid = read();
        assertThat(paid.status()).isEqualTo(ChargeStatus.PAID);
        assertThat(paid.paidAt()).isEqualTo(credit.acceptedAt());
        assertThat(payment(credit))
                .containsEntry("outcome", "SETTLED")
                .containsEntry("review_reason", null)
                .containsEntry("amount_minor", 10_000L)
                .containsEntry("amount_refunded", 0L);
        assertThat(available()).isEqualTo(brl(10_000));
    }

    @Test
    void postsTheCreditFromTheSettlementAccountToTheTenantsOnItsBusinessDate() {
        // 01:30 UTC on November 23 is 22:30 on November 22 in São Paulo. The charge lasts long enough
        // for that time to be before its expiry, wherever other tests moved the clock.
        charge = createAnotherCharge(Duration.ofDays(7));
        PixCredit credit = credit(10_000, Instant.parse("2026-11-23T01:30:00Z"));

        settlement.handle(credit);

        Map<String, @Nullable Object> entry =
                owner.sql("""
                        SELECT id, tenant_id, effective_date, correlation_id
                          FROM ledger.entries
                         WHERE source_type = 'pix_credit' AND source_id = ? AND purpose = 'settlement'
                        """).param(credit.endToEndId()).query().singleRow();
        assertThat(entry)
                .containsEntry("tenant_id", organization.test().value())
                .containsEntry("effective_date", Date.valueOf(LocalDate.parse("2026-11-22")))
                .containsEntry("correlation_id", credit.messageId());
        assertThat(payment(credit)).containsEntry("entry_id", entry.get("id"));
        assertThat(owner.sql("""
                        SELECT a.code, p.direction, p.amount
                          FROM ledger.postings p JOIN ledger.accounts a ON a.id = p.account_id
                         WHERE p.entry_id = ?
                         ORDER BY p.direction
                        """)
                        .param(entry.get("id"))
                        .query((row, rowNumber) ->
                                row.getString("code") + " " + row.getString("direction") + " " + row.getLong("amount"))
                        .list())
                .containsExactly(
                        "tenant:" + organization.test().value() + ":available CREDIT 10000",
                        "platform:settlement DEBIT 10000");
    }

    @Test
    void settlesWithAPaymentAcceptedOneMillisecondBeforeTheExpiry() {
        PixCredit credit = credit(10_000, charge.expiresAt().minusMillis(1));

        settlement.handle(credit);

        assertThat(read().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(payment(credit)).containsEntry("outcome", "SETTLED");
    }

    @Test
    void settlesAnActiveChargeWhoseExpiryPassedWithAPaymentAcceptedBeforeIt() {
        PixCredit credit = credit(10_000, charge.expiresAt().minusSeconds(5));
        clock.advance(Duration.ofHours(2));

        settlement.handle(credit);

        assertThat(read().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(read().paidAt()).isEqualTo(credit.acceptedAt());
    }

    @Test
    void flagsAPaymentAcceptedAtTheExpiryAndExpiresTheCharge() {
        clock.advance(Duration.ofMinutes(31));
        PixCredit credit = credit(10_000, charge.expiresAt());

        settlement.handle(credit);

        assertThat(read().status()).isEqualTo(ChargeStatus.EXPIRED);
        assertThat(read().paidAt()).isNull();
        assertThat(payment(credit))
                .containsEntry("outcome", "FLAGGED")
                .containsEntry("review_reason", "CHARGE_EXPIRED");
        assertThat(available()).isEqualTo(brl(10_000));
    }

    @Test
    void settlesAChargeTheJobExpiredWithAPaymentAcceptedBeforeItsExpiry() {
        PixCredit credit = credit(10_000, charge.expiresAt().minusSeconds(5));
        clock.advance(Duration.ofMinutes(31));
        expiry.expireDue();
        assertThat(read().status()).isEqualTo(ChargeStatus.EXPIRED);

        settlement.handle(credit);

        assertThat(read().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(read().paidAt()).isEqualTo(credit.acceptedAt());
        assertThat(payment(credit)).containsEntry("outcome", "SETTLED");
        assertThat(available()).isEqualTo(brl(10_000));
    }

    @Test
    void flagsAPaymentAcceptedAfterTheExpiryOfAChargeTheJobExpiredAndCreditsIt() {
        clock.advance(Duration.ofMinutes(31));
        expiry.expireDue();
        PixCredit credit = credit(10_000, charge.expiresAt().plusSeconds(10));

        settlement.handle(credit);

        assertThat(read().status()).isEqualTo(ChargeStatus.EXPIRED);
        assertThat(read().paidAt()).isNull();
        assertThat(payment(credit))
                .containsEntry("outcome", "FLAGGED")
                .containsEntry("review_reason", "CHARGE_EXPIRED");
        assertThat(available()).isEqualTo(brl(10_000));
    }

    @Test
    void flagsAPaymentOfAnotherAmountAndLeavesTheChargeActive() {
        PixCredit credit = credit(9_999, charge.createdAt().plusSeconds(60));

        settlement.handle(credit);

        assertThat(read().status()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(payment(credit))
                .containsEntry("outcome", "FLAGGED")
                .containsEntry("review_reason", "AMOUNT_MISMATCH")
                .containsEntry("amount_minor", 9_999L);
        assertThat(available()).isEqualTo(brl(9_999));
    }

    @Test
    void flagsASecondPaymentOfAPaidChargeAndCreditsBoth() {
        PixCredit first = credit(10_000, charge.createdAt().plusSeconds(60));
        PixCredit second = credit(10_000, charge.createdAt().plusSeconds(120));

        settlement.handle(first);
        assertThat(settlement.handle(second)).isEqualTo(CreditResult.RECORDED);

        assertThat(read().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(read().paidAt()).isEqualTo(first.acceptedAt());
        assertThat(payment(second))
                .containsEntry("outcome", "FLAGGED")
                .containsEntry("review_reason", "CHARGE_ALREADY_PAID");
        assertThat(available()).isEqualTo(brl(20_000));
    }

    @Test
    void flagsAPaymentOfAPartiallyRefundedChargeAndLeavesItsRefundsAlone() {
        PixCredit first = credit(10_000, charge.createdAt().plusSeconds(60));
        PixCredit second = credit(10_000, charge.createdAt().plusSeconds(120));
        settlement.handle(first);
        var partial = new RefundRequest(Optional.empty(), Optional.of(brl(2_500)), "req-refund");
        RefundResult refund =
                TenantContext.call(organization.test(), Environment.TEST, () -> billing.refund(charge.id(), partial));
        assertThat(refund).isInstanceOf(RefundResult.Refunded.class);

        assertThat(settlement.handle(second)).isEqualTo(CreditResult.RECORDED);

        assertThat(read().status()).isEqualTo(ChargeStatus.PARTIALLY_REFUNDED);
        assertThat(read().amountRefunded()).isEqualTo(brl(2_500));
        assertThat(payment(second))
                .containsEntry("outcome", "FLAGGED")
                .containsEntry("review_reason", "CHARGE_ALREADY_PAID");
        assertThat(available()).isEqualTo(brl(17_500));
    }

    @Test
    void settlesWithAPaymentOfItsAmountAfterOneOfAnotherAmount() {
        settlement.handle(credit(5_000, charge.createdAt().plusSeconds(60)));
        PixCredit exact = credit(10_000, charge.createdAt().plusSeconds(120));

        settlement.handle(exact);

        assertThat(read().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(payment(exact)).containsEntry("outcome", "SETTLED");
        assertThat(available()).isEqualTo(brl(15_000));
    }

    @Test
    void recordsACreditHandledTwiceOnce() {
        PixCredit credit = credit(10_000, charge.createdAt().plusSeconds(60));

        assertThat(settlement.handle(credit)).isEqualTo(CreditResult.RECORDED);
        assertThat(settlement.handle(credit)).isEqualTo(CreditResult.ALREADY_RECORDED);

        assertThat(paymentsOfTheCharge()).isOne();
        assertThat(entriesOf(credit)).isOne();
        assertThat(available()).isEqualTo(brl(10_000));
    }

    @Test
    void recordsNothingForATransactionIdNoChargeHas() {
        var credit = new PixCredit(
                BillingFixtures.endToEndId(),
                messageId(),
                random(25),
                brl(10_000),
                charge.createdAt().plusSeconds(60),
                "99990011");

        assertThat(settlement.handle(credit)).isEqualTo(CreditResult.UNKNOWN_TRANSACTION);

        assertThat(entriesOf(credit)).isZero();
        assertThat(owner.sql("SELECT count(*) FROM billing.payments WHERE end_to_end_id = ?")
                        .param(credit.endToEndId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void recordsACreditHandledByTwoProcessorsAtOnceOnce() throws Exception {
        PixCredit credit = credit(10_000, charge.createdAt().plusSeconds(60));

        List<CreditResult> results = atOnce(() -> settlement.handle(credit), () -> settlement.handle(credit));

        assertThat(results).containsExactlyInAnyOrder(CreditResult.RECORDED, CreditResult.ALREADY_RECORDED);
        assertThat(paymentsOfTheCharge()).isOne();
        assertThat(entriesOf(credit)).isOne();
        assertThat(available()).isEqualTo(brl(10_000));
    }

    @Test
    void settlesWithOneOfTwoEqualPaymentsHandledAtOnceAndFlagsTheOther() throws Exception {
        PixCredit first = credit(10_000, charge.createdAt().plusSeconds(60));
        PixCredit second = credit(10_000, charge.createdAt().plusSeconds(61));

        atOnce(() -> settlement.handle(first), () -> settlement.handle(second));

        assertThat(owner.sql("""
                        SELECT outcome, review_reason FROM billing.payments WHERE charge_id = ?
                        """)
                        .param(charge.id().value())
                        .query((row, rowNumber) -> row.getString("outcome") + " " + row.getString("review_reason"))
                        .list())
                .containsExactlyInAnyOrder("SETTLED null", "FLAGGED CHARGE_ALREADY_PAID");
        assertThat(read().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(available()).isEqualTo(brl(20_000));
    }

    @Test
    void countsEachPaymentByWhatItDidToItsCharge() {
        double settled = payments("settled", "none");
        double mismatched = payments("flagged", "amount_mismatch");

        settlement.handle(credit(9_999, charge.createdAt()));
        settlement.handle(credit(10_000, charge.createdAt()));

        assertThat(payments("settled", "none")).isEqualTo(settled + 1);
        assertThat(payments("flagged", "amount_mismatch")).isEqualTo(mismatched + 1);
    }

    @Test
    void timesSettlementFromTheRailsAcceptanceOfThePayment() {
        Timer lag = meters.get(Settlement.SETTLEMENT_LAG).timer();
        long settled = lag.count();
        double waited = lag.totalTime(TimeUnit.SECONDS);
        PixCredit credit = credit(10_000, clock.instant());
        clock.advance(Duration.ofSeconds(45));

        settlement.handle(credit);

        assertThat(lag.count()).isEqualTo(settled + 1);
        assertThat(lag.totalTime(TimeUnit.SECONDS)).isEqualTo(waited + 45);
    }

    @Test
    void countsNothingForACreditRecordedAlready() {
        PixCredit credit = credit(10_000, charge.createdAt());
        settlement.handle(credit);
        double settled = payments("settled", "none");

        settlement.handle(credit);

        assertThat(payments("settled", "none")).isEqualTo(settled);
    }

    private double payments(String outcome, String reason) {
        return meters.get(Settlement.PAYMENTS)
                .tag("outcome", outcome)
                .tag("reason", reason)
                .counter()
                .count();
    }

    private ChargeView createAnotherCharge(Duration expiresIn) {
        return TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> created(billing.createCharge(new ChargeRequest(brl(10_000), null, expiresIn, Map.of()))));
    }

    /** A credit for this test's charge, as the rails module hands it over. */
    private PixCredit credit(long minorUnits, Instant acceptedAt) {
        return new PixCredit(
                BillingFixtures.endToEndId(),
                messageId(),
                charge.transactionId(),
                brl(minorUnits),
                acceptedAt,
                "99990011");
    }

    private ChargeView read() {
        return TenantContext.call(organization.test(), Environment.TEST, () -> billing.charge(charge.id()))
                .orElseThrow()
                .charge();
    }

    private Money available() {
        var account = new AccountRequest(
                organization.test().value(),
                "tenant:" + organization.test().value() + ":available",
                AccountType.LIABILITY,
                Charge.CURRENCY,
                false,
                false);
        return TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> ledger.balanceOf(ledger.openAccount(account)).balance());
    }

    private Map<String, @Nullable Object> payment(PixCredit credit) {
        return owner.sql("SELECT * FROM billing.payments WHERE end_to_end_id = ?")
                .param(credit.endToEndId())
                .query()
                .singleRow();
    }

    private long paymentsOfTheCharge() {
        return owner.sql("SELECT count(*) FROM billing.payments WHERE charge_id = ?")
                .param(charge.id().value())
                .query(Long.class)
                .single();
    }

    private long entriesOf(PixCredit credit) {
        return owner.sql("SELECT count(*) FROM ledger.entries WHERE source_type = 'pix_credit' AND source_id = ?")
                .param(credit.endToEndId())
                .query(Long.class)
                .single();
    }

    /** Runs both together, from a common start, and waits for both. */
    @SafeVarargs
    private static <T> List<T> atOnce(Callable<T>... work) throws Exception {
        var start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(work.length)) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : work) {
                futures.add(threads.submit(() -> {
                    start.await();
                    return task.call();
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

    private static String messageId() {
        return "M99990011" + random(23);
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
