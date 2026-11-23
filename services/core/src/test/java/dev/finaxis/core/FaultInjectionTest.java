package dev.finaxis.core;

import static dev.finaxis.core.billing.BillingFixtures.brl;
import static dev.finaxis.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.finaxis.core.billing.BillingApi;
import dev.finaxis.core.billing.ChargeDetail;
import dev.finaxis.core.billing.ChargeRequest;
import dev.finaxis.core.billing.ChargeStatus;
import dev.finaxis.core.billing.ChargeView;
import dev.finaxis.core.billing.PaymentOutcome;
import dev.finaxis.core.billing.ReviewReason;
import dev.finaxis.core.ledger.AccountRequest;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.ledger.application.BalanceVerifier;
import dev.finaxis.core.ledger.application.LedgerVerification;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The fault-injection suite (billing design, section 13.1): the core, with its processor and its
 * expiry job on their schedules, receives the payments of the rails simulator's image while the
 * chaos API disturbs their delivery. Each scenario pays BR Codes the core issued, so the simulator's
 * own reader checks them, and ends with the ledger's balance verifier.
 *
 * <p>Run with {@code ./gradlew faultInjectionTest} after {@code just image rails-simulator}.
 */
@Tag(FaultInjectionTest.TAG)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"finaxis.rails.processor.enabled=true", "finaxis.billing.expiry.enabled=true"})
@Import(PostgresTestConfiguration.class)
class FaultInjectionTest {

    static final String TAG = "fault-injection";

    private static final int CORE_PORT = RailsSimulator.freePort();

    private static final RailsSimulator RAILS = RailsSimulator.start(CORE_PORT);

    /** Long enough for a notification and its retries to reach the core and be settled. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** The shortest a charge lasts, then a pass of the expiry job, every 30 seconds, and a margin. */
    private static final Duration EXPIRY = ChargeRequest.MIN_EXPIRES_IN.plusSeconds(45);

    /** The payments of the outage scenario: as many charges as a test tenant may create in an hour. */
    private static final int OUTAGE_PAYMENTS = 200;

    @DynamicPropertySource
    static void rails(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> CORE_PORT);
        registry.add("finaxis.rails.signing.keys[0].id", RAILS::keyId);
        registry.add("finaxis.rails.signing.keys[0].public-key", RAILS::publicKey);
    }

    @Autowired
    private BillingApi billing;

    @Autowired
    private TenancyApi tenancy;

    @Autowired
    private LedgerApi ledger;

    @Autowired
    private DatabaseOwner owner;

    @Autowired
    private BalanceVerifier verifier;

    private NewOrganization organization;

    @BeforeEach
    void createTenant() {
        organization = tenancy.createOrganization("Acme", "subject-" + UUID.randomUUID());
    }

    @AfterEach
    void leavesTheLedgerSound() {
        RAILS.reset();
        assertThat(verifier.verify()).isEqualTo(new LedgerVerification(List.of(), List.of(), List.of()));
    }

    @Test
    void postsDuplicatesOnce() throws InterruptedException {
        ChargeView charge = charge(10_000, ChargeRequest.DEFAULT_EXPIRES_IN);
        RAILS.addRule("""
                {"fault": "duplicate", "copies": 3, "match": {"transactionId": "%s"}, "times": 1}""".formatted(charge.transactionId()));

        String endToEndId = RAILS.pay(charge.brCode());

        ChargeDetail paid =
                await(() -> detail(charge), detail -> detail.charge().status() == ChargeStatus.PAID);
        assertThat(paid.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.endToEndId()).isEqualTo(endToEndId);
            assertThat(payment.outcome()).isEqualTo(PaymentOutcome.SETTLED);
        });
        await(() -> deliveries(endToEndId), delivered(4));
        assertThat(entriesOf(endToEndId)).isOne();
        assertThat(available()).isEqualTo(brl(10_000));
    }

    @Test
    void survivesLostAcknowledgment() throws InterruptedException {
        ChargeView charge = charge(10_000, ChargeRequest.DEFAULT_EXPIRES_IN);
        RAILS.addRule("""
                {"fault": "lost_response", "match": {"transactionId": "%s"}, "times": 1}""".formatted(charge.transactionId()));

        String endToEndId = RAILS.pay(charge.brCode());

        await(() -> detail(charge), detail -> detail.charge().status() == ChargeStatus.PAID);
        await(() -> deliveries(endToEndId), delivered(1));
        String payment = RAILS.payment(endToEndId);
        List<Integer> answers = JsonPath.read(payment, "$.deliveries[0].attempts[*].responseStatus");
        assertThat(answers).containsExactly(202, 202);
        List<String> outcomes = JsonPath.read(payment, "$.deliveries[0].attempts[*].outcome");
        assertThat(outcomes).containsExactly("retried", "delivered");
        assertThat(detail(charge).payments()).hasSize(1);
        assertThat(entriesOf(endToEndId)).isOne();
        assertThat(available()).isEqualTo(brl(10_000));
    }

    @Test
    void settlesByFirstNotified() throws InterruptedException {
        ChargeView charge = charge(10_000, ChargeRequest.DEFAULT_EXPIRES_IN);
        RAILS.addRule("""
                {"fault": "reorder", "match": {"transactionId": "%s"}, "times": 2}""".formatted(charge.transactionId()));

        String paidFirst = RAILS.pay(charge.brCode());
        String paidSecond = RAILS.pay(charge.brCode());

        ChargeDetail both =
                await(() -> detail(charge), detail -> detail.payments().size() == 2);
        assertThat(both.charge().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(both.payments())
                .satisfiesExactly(
                        settling -> {
                            assertThat(settling.endToEndId()).isEqualTo(paidSecond);
                            assertThat(settling.outcome()).isEqualTo(PaymentOutcome.SETTLED);
                        },
                        flagged -> {
                            assertThat(flagged.endToEndId()).isEqualTo(paidFirst);
                            assertThat(flagged.outcome()).isEqualTo(PaymentOutcome.FLAGGED);
                            assertThat(flagged.reviewReason()).contains(ReviewReason.CHARGE_ALREADY_PAID);
                        });
        assertThat(available()).isEqualTo(brl(20_000));
    }

    @Test
    void settlesThroughRetries() throws InterruptedException {
        ChargeView charge = charge(10_000, ChargeRequest.DEFAULT_EXPIRES_IN);
        RAILS.addRule("""
                {"fault": "fail", "match": {"transactionId": "%s"}, "times": 3}""".formatted(charge.transactionId()));
        RAILS.addRule("""
                {"fault": "latency", "min": "PT1S", "max": "PT3S", "match": {"transactionId": "%s"}, "times": 4}""".formatted(charge.transactionId()));

        String endToEndId = RAILS.pay(charge.brCode());

        ChargeDetail paid =
                await(() -> detail(charge), detail -> detail.charge().status() == ChargeStatus.PAID);
        assertThat(paid.payments()).hasSize(1);
        await(() -> deliveries(endToEndId), delivered(1));
        String payment = RAILS.payment(endToEndId);
        List<String> errors = JsonPath.read(payment, "$.deliveries[0].attempts[*].error");
        assertThat(errors).containsExactly("not_sent", "not_sent", "not_sent");
        List<String> outcomes = JsonPath.read(payment, "$.deliveries[0].attempts[*].outcome");
        assertThat(outcomes).containsExactly("retried", "retried", "retried", "delivered");
        List<String> faults = JsonPath.read(payment, "$.deliveries[0].faults[*].fault");
        assertThat(faults).filteredOn("latency"::equals).hasSize(4);
        assertThat(entriesOf(endToEndId)).isOne();
        assertThat(available()).isEqualTo(brl(10_000));
    }

    /**
     * The payer pays a code whose charge expired. The expiry job may not have run yet, so the outcome
     * cannot depend on it: the payment is flagged by its acceptance time either way.
     */
    @Test
    void flagsLatePayment() throws InterruptedException {
        ChargeView charge = charge(10_000, ChargeRequest.MIN_EXPIRES_IN);
        sleepUntil(charge.expiresAt().plusSeconds(1));

        String endToEndId = RAILS.pay(charge.brCode());

        ChargeDetail flagged =
                await(() -> detail(charge), detail -> detail.payments().size() == 1);
        assertThat(flagged.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.endToEndId()).isEqualTo(endToEndId);
            assertThat(payment.outcome()).isEqualTo(PaymentOutcome.FLAGGED);
            assertThat(payment.reviewReason()).contains(ReviewReason.CHARGE_EXPIRED);
        });
        assertThat(flagged.charge().status()).isNotEqualTo(ChargeStatus.PAID);
        await(EXPIRY, () -> detail(charge), detail -> detail.charge().status() == ChargeStatus.EXPIRED);
        assertThat(available()).isEqualTo(brl(10_000));
    }

    /**
     * The payer pays in time, and the notification is held until the expiry job has expired the
     * charge: the payment still settles it, as of its acceptance time.
     */
    @Test
    void settlesLateNotification() throws InterruptedException {
        ChargeView charge = charge(10_000, ChargeRequest.MIN_EXPIRES_IN);
        Duration delay = EXPIRY.plusSeconds(15);
        RAILS.addRule("""
                {"fault": "delay", "duration": "%s", "match": {"transactionId": "%s"}, "times": 1}""".formatted(RailsSimulator.seconds(delay), charge.transactionId()));

        String endToEndId = RAILS.pay(charge.brCode());
        Instant acceptedAt = Instant.parse(JsonPath.read(RAILS.payment(endToEndId), "$.settledAt"));
        assertThat(acceptedAt).isBefore(charge.expiresAt());

        ChargeDetail expired =
                await(EXPIRY, () -> detail(charge), detail -> detail.charge().status() == ChargeStatus.EXPIRED);
        assertThat(expired.payments()).isEmpty();
        ChargeDetail paid = await(
                delay.plus(PATIENCE),
                () -> detail(charge),
                detail -> detail.charge().status() == ChargeStatus.PAID);
        assertThat(paid.charge().paidAt()).isEqualTo(acceptedAt);
        assertThat(paid.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.endToEndId()).isEqualTo(endToEndId);
            assertThat(payment.outcome()).isEqualTo(PaymentOutcome.SETTLED);
        });
        assertThat(available()).isEqualTo(brl(10_000));
    }

    /**
     * Payers pay 200 codes; their notifications are postponed past the start of a one-minute outage,
     * so the rails hold every one of them until it ends. Then they drain at once, and each payment
     * settles its charge once.
     */
    @Test
    void drainsAfterOutage() throws Exception {
        List<ChargeView> charges = new ArrayList<>();
        for (int i = 0; i < OUTAGE_PAYMENTS; i++) {
            charges.add(charge(100, ChargeRequest.DEFAULT_EXPIRES_IN));
        }
        Duration delay = Duration.ofSeconds(30);
        RAILS.addRule("""
                {"fault": "delay", "duration": "%s", "match": {}, "times": %d}""".formatted(RailsSimulator.seconds(delay), OUTAGE_PAYMENTS));

        Instant start = Instant.now();
        try (var payers = Executors.newFixedThreadPool(8)) {
            for (Future<String> payment : payers.invokeAll(charges.stream()
                    .<Callable<String>>map(charge -> () -> RAILS.pay(charge.brCode()))
                    .toList())) {
                payment.get();
            }
        }
        Duration outage = Duration.ofMinutes(1);
        RAILS.startOutage(outage);
        assertThat(Duration.between(start, Instant.now()))
                .as("the payments were made before their notifications were due")
                .isLessThan(delay);

        sleepUntil(start.plus(outage).minusSeconds(5));
        assertThat(credits()).as("credits received during the outage").isZero();
        await(outage.plus(PATIENCE), this::paidCharges, paid -> paid == OUTAGE_PAYMENTS);
        assertThat(credits()).isEqualTo(OUTAGE_PAYMENTS);
        assertThat(pendingCredits()).isZero();
        assertThat(owner.jdbc()
                        .sql("""
                                SELECT count(*) FROM ledger.entries e
                                  JOIN rails.pix_credits c ON c.end_to_end_id = e.source_id
                                  JOIN billing.charges ch ON ch.transaction_id = c.transaction_id
                                 WHERE e.source_type = 'pix_credit' AND ch.tenant_id = ?""")
                        .param(organization.test().value())
                        .query(Long.class)
                        .single())
                .isEqualTo(OUTAGE_PAYMENTS);
        assertThat(available()).isEqualTo(brl(100L * OUTAGE_PAYMENTS));
    }

    /**
     * Settlement fails at commit, after the payment and its entry were written: nothing of the attempt
     * remains, and the processor's retry settles the charge once. No fault of the rails can stop the
     * core between two statements, so the failure is a trigger the test adds to the core's database.
     */
    @Test
    void rollsBackFailedSettlement() throws Exception {
        ChargeView charge = charge(10_000, ChargeRequest.DEFAULT_EXPIRES_IN);
        String endToEndId;
        try (var _ = failAtCommit(charge)) {
            endToEndId = RAILS.pay(charge.brCode());

            await(() -> attemptsOf(endToEndId), attempts -> attempts > 0);
            assertThat(detail(charge).charge().status()).isEqualTo(ChargeStatus.ACTIVE);
            assertThat(detail(charge).payments()).isEmpty();
            assertThat(entriesOf(endToEndId)).isZero();
        }

        ChargeDetail paid =
                await(() -> detail(charge), detail -> detail.charge().status() == ChargeStatus.PAID);
        assertThat(paid.payments()).hasSize(1);
        assertThat(entriesOf(endToEndId)).isOne();
        assertThat(available()).isEqualTo(brl(10_000));
    }

    /**
     * Fails every commit that records a payment of the charge, until closed. Deferred, so it fires
     * at commit, once the payment and its entry are written.
     */
    private AutoCloseable failAtCommit(ChargeView charge) {
        owner.jdbc().sql("""
                CREATE FUNCTION billing.fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'injected failure at commit';
                END
                $$""").update();
        owner.jdbc().sql("""
                CREATE CONSTRAINT TRIGGER fail_at_commit AFTER INSERT ON billing.payments
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW WHEN (NEW.charge_id = '%s')
                    EXECUTE FUNCTION billing.fail_at_commit()""".formatted(charge.id().value())).update();
        return () -> {
            owner.jdbc().sql("DROP TRIGGER fail_at_commit ON billing.payments").update();
            owner.jdbc().sql("DROP FUNCTION billing.fail_at_commit()").update();
        };
    }

    /** How often the core's processor failed to hand the credit to billing. */
    private int attemptsOf(String endToEndId) {
        return owner.jdbc()
                .sql("SELECT coalesce(max(attempts), 0) FROM rails.pix_credits WHERE end_to_end_id = ?")
                .param(endToEndId)
                .query(Integer.class)
                .single();
    }

    private ChargeView charge(long amount, Duration expiresIn) {
        return TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> created(billing.createCharge(new ChargeRequest(brl(amount), null, expiresIn, Map.of()))));
    }

    private ChargeDetail detail(ChargeView charge) {
        return TenantContext.call(organization.test(), Environment.TEST, () -> billing.charge(charge.id()))
                .orElseThrow();
    }

    /** The statuses of the payment's deliveries in the simulator, oldest first. */
    private static List<String> deliveries(String endToEndId) {
        return JsonPath.read(RAILS.payment(endToEndId), "$.deliveries[*].status");
    }

    private static Predicate<List<String>> delivered(int count) {
        return statuses -> statuses.size() == count && statuses.stream().allMatch("delivered"::equals);
    }

    /** The tenant's available balance, which every payment credits. */
    private Money available() {
        var account = new AccountRequest(
                organization.test().value(),
                "tenant:" + organization.test().value() + ":available",
                AccountType.LIABILITY,
                brl(0).currency(),
                false,
                false);
        return TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> ledger.balanceOf(ledger.openAccount(account)).balance());
    }

    /** Read as the owner, so Row-Level Security cannot hide an entry that was posted. */
    private long entriesOf(String endToEndId) {
        return owner.jdbc()
                .sql("SELECT count(*) FROM ledger.entries WHERE source_type = 'pix_credit' AND source_id = ?")
                .param(endToEndId)
                .query(Long.class)
                .single();
    }

    private long paidCharges() {
        return owner.jdbc()
                .sql("SELECT count(*) FROM billing.charges WHERE tenant_id = ? AND status = 'PAID'")
                .param(organization.test().value())
                .query(Long.class)
                .single();
    }

    /** The tenant's credits in the core's inbox. */
    private long credits() {
        return creditsWhere("true");
    }

    /** The tenant's credits in the inbox still to hand to billing. */
    private long pendingCredits() {
        return creditsWhere("c.status = 'received'");
    }

    private long creditsWhere(String condition) {
        return owner.jdbc()
                .sql("""
                        SELECT count(*) FROM rails.pix_credits c
                          JOIN billing.charges ch ON ch.transaction_id = c.transaction_id
                         WHERE ch.tenant_id = ? AND\s""" + condition)
                .param(organization.test().value())
                .query(Long.class)
                .single();
    }

    private static void sleepUntil(Instant moment) throws InterruptedException {
        Duration left = Duration.between(Instant.now(), moment);
        if (left.isPositive()) {
            Thread.sleep(left);
        }
    }

    /** Polls until the value satisfies the condition, then returns it; fails after {@link #PATIENCE}. */
    private static <T> T await(Supplier<T> value, Predicate<T> condition) throws InterruptedException {
        return await(PATIENCE, value, condition);
    }

    private static <T> T await(Duration patience, Supplier<T> value, Predicate<T> condition)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(patience);
        while (true) {
            T now = value.get();
            if (condition.test(now)) {
                return now;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Still " + now + " after " + patience);
            }
            Thread.sleep(200);
        }
    }
}
