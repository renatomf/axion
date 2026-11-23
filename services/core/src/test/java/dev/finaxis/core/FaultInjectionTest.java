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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
