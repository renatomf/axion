package dev.axion.core.billing.web;

import static dev.axion.core.billing.BillingFixtures.brl;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.MutableClock;
import dev.axion.core.OpenApiContract;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.access.AccessApi;
import dev.axion.core.access.AccessFixtures;
import dev.axion.core.access.IssuedApiKey;
import dev.axion.core.access.TenantBoundAccess;
import dev.axion.core.billing.BillingApi;
import dev.axion.core.billing.BillingFixtures;
import dev.axion.core.billing.ChargeRequest;
import dev.axion.core.billing.ChargeStatus;
import dev.axion.core.billing.ChargeView;
import dev.axion.core.billing.PaymentId;
import dev.axion.core.billing.RefundRequest;
import dev.axion.core.billing.RefundResult;
import dev.axion.core.billing.domain.Charge;
import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.AccountRequest;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.EntryRequest;
import dev.axion.core.ledger.EntrySource;
import dev.axion.core.ledger.LedgerApi;
import dev.axion.core.ledger.PostResult;
import dev.axion.core.ledger.PostingRequest;
import dev.axion.core.ledger.application.BalanceVerifier;
import dev.axion.core.ledger.application.LedgerVerification;
import dev.axion.core.rails.CreditResult;
import dev.axion.core.rails.PixCredit;
import dev.axion.core.rails.PixCreditHandler;
import dev.axion.core.shared.BusinessCalendar;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.Money;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.TenancyApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/charges/{id}/refunds} (billing design, sections 10 and 12.2), every response
 * checked against the contract. The clock stands still until a test moves it.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, RefundsControllerTest.Configuration.class})
@AutoConfigureMockMvc
class RefundsControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-22T14:30:00Z"));
        }
    }

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final BillingApi billing;
    private final PixCreditHandler settlement;
    private final LedgerApi ledger;
    private final BalanceVerifier verifier;
    private final JdbcClient owner;
    private final JsonMapper json;
    private final MutableClock clock;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey refunder;
    private ChargeView charge;

    @Autowired
    RefundsControllerTest(
            MockMvcTester mvc,
            AccessApi access,
            TenancyApi tenancy,
            BillingApi billing,
            PixCreditHandler settlement,
            LedgerApi ledger,
            BalanceVerifier verifier,
            DatabaseOwner owner,
            JsonMapper json,
            MutableClock clock) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.billing = billing;
        this.settlement = settlement;
        this.ledger = ledger;
        this.verifier = verifier;
        this.owner = owner.jdbc();
        this.json = json;
        this.clock = clock;
    }

    /** A charge of R$ 100.00 of a new tenant, expiring in 30 minutes, and a key that may refund it. */
    @BeforeEach
    void createCharge() {
        organization =
                tenancy.createOrganization("Padaria São João", UUID.randomUUID().toString());
        refunder = issued(Environment.TEST, Permission.REFUNDS_WRITE);
        charge = TestCaller.call(
                organization.test(),
                Environment.TEST,
                () -> BillingFixtures.created(
                        billing.createCharge(new ChargeRequest(brl(10_000), null, Duration.ofMinutes(30), Map.of()))));
    }

    @AfterEach
    void leavesTheLedgerSound() {
        assertThat(verifier.verify()).isEqualTo(new LedgerVerification(List.of(), List.of(), List.of()));
    }

    @Test
    void refundsAllOfTheOnlyPaymentWhenTheBodyIsEmpty() {
        PixCredit credit = pay(10_000);

        MvcTestResult result = refund("{}");

        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .hasContentType(MediaType.APPLICATION_JSON)
                .doesNotContainHeader("Location");
        JsonNode refund = body(result);
        assertThat(refund.path("id").asString()).startsWith("re_");
        assertThat(refund).isEqualTo(json.readTree("""
                        {
                          "id": "%s",
                          "object": "refund",
                          "charge": "%s",
                          "payment": "%s",
                          "amount": 10000,
                          "status": "succeeded",
                          "created_at": "%s"
                        }
                        """.formatted(
                        refund.path("id").asString(),
                        charge.id().publicId(),
                        paymentId(credit).publicId(),
                        refund.path("created_at").asString())));
        assertThat(Instant.parse(refund.path("created_at").asString())).isEqualTo(clock.instant());
        assertThat(read().status()).isEqualTo(ChargeStatus.REFUNDED);
    }

    @Test
    void refundsPartOfTheSettlingPayment() {
        pay(10_000);

        MvcTestResult result = refund("""
                {"amount": 2500}
                """);

        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.amount")
                .isEqualTo(2500);
        assertThat(read().status()).isEqualTo(ChargeStatus.PARTIALLY_REFUNDED);
        assertThat(read().amountRefunded()).isEqualTo(brl(2_500));
    }

    @Test
    void refundsTheNamedPaymentOfAChargeWithSeveral() {
        pay(10_000);
        PixCredit flagged = pay(10_000);

        MvcTestResult result = refund("""
                {"amount": 4000, "payment": "%s"}
                """.formatted(paymentId(flagged).publicId()));

        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.payment")
                .isEqualTo(paymentId(flagged).publicId().toString());
        assertThat(read().status()).isEqualTo(ChargeStatus.PAID);
    }

    @Test
    void recordsTheRequestsCorrelationIdWithTheRefund() {
        pay(10_000);

        MvcTestResult result = send(mvc.post()
                .uri(path())
                .header("Authorization", "Bearer " + refunder.secret())
                .header("Idempotency-Key", "refund-1")
                .header("X-Correlation-Id", "support-ticket-4417")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(owner.sql("SELECT correlation_id FROM billing.refunds WHERE charge_id = ?")
                        .param(charge.id().value())
                        .query(String.class)
                        .single())
                .isEqualTo("support-ticket-4417");
    }

    @Test
    void refusesARefundOfAChargeWithNoPayment() {
        assertRefused(refund("{}"), "charge_not_paid");
    }

    @Test
    void refusesARefundThatNamesNoPaymentOfAChargeWithSeveral() {
        pay(10_000);
        pay(10_000);

        assertRefused(refund("{}"), "payment_required");
    }

    @Test
    void refusesAPaymentOfAnotherChargeNamingTheField() {
        pay(10_000);

        MvcTestResult result = refund("""
                {"payment": "%s"}
                """.formatted(new PaymentId(UUID.randomUUID()).publicId()));

        assertRefused(result, "validation_failed");
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").isEqualTo(List.of("payment"));
    }

    @Test
    void refusesARefundOfMoreThanRemainsOfThePayment() {
        pay(10_000);

        assertRefused(refund("""
                {"amount": 10001}
                """), "amount_exceeds_refundable");
    }

    @Test
    void refusesARefundMoreThanNinetyDaysAfterThePaymentWasAccepted() {
        pay(10_000);
        clock.advance(Charge.REFUND_WINDOW.plusSeconds(1));

        assertRefused(refund("{}"), "refund_window_closed");
    }

    @Test
    void refusesARefundBeyondTheFiftiethOfACharge() {
        pay(10_000);
        for (int i = 0; i < Charge.MAX_REFUNDS; i++) {
            RefundResult made = TestCaller.call(
                    organization.test(),
                    Environment.TEST,
                    () -> billing.refund(
                            charge.id(),
                            new RefundRequest(Optional.empty(), Optional.of(brl(1)), "req-" + UUID.randomUUID())));
            assertThat(made).isInstanceOf(RefundResult.Refunded.class);
        }

        MvcTestResult result = refund("""
                {"amount": 1}
                """);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("refund_limit_reached");
        assertThat(refunds()).isEqualTo(Charge.MAX_REFUNDS);
    }

    @Test
    void refusesARefundTheTenantsBalanceCannotCover() {
        pay(10_000);
        hold(brl(9_000));

        assertRefused(refund("""
                {"amount": 1001}
                """), "insufficient_balance");
        assertThat(read().status()).isEqualTo(ChargeStatus.PAID);
    }

    @Test
    void answersNotFoundForAnUnknownCharge() {
        assertNotFound(refund(refunder, "/v1/charges/ch_01jdq8k3m4n5p6q7r8s9t0v1w2/refunds", "refund-1", "{}"));
    }

    @Test
    void answersNotFoundForAMalformedChargeId() {
        assertNotFound(refund(refunder, "/v1/charges/ch_malformed/refunds", "refund-1", "{}"));
    }

    @Test
    void answersNotFoundForAChargeOfTheOtherEnvironment() {
        pay(10_000);

        assertNotFound(refund(issued(Environment.LIVE, Permission.REFUNDS_WRITE), path(), "refund-1", "{}"));
        assertThat(refunds()).isZero();
    }

    @Test
    void refusesFieldsOutsideTheirRulesNamingEach() {
        pay(10_000);

        MvcTestResult result = refund("""
                {"amount": 0, "payment": "pmt_x"}
                """);

        assertRefused(result, "validation_failed");
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").isEqualTo(List.of("amount", "payment"));
        assertThat(refund("""
                        {"amount": 100, "currency": "BRL"}
                        """)).bodyJson().extractingPath("$.errors[*].field").isEqualTo(List.of("currency"));
    }

    @Test
    void refusesAValueOfAnotherType() {
        pay(10_000);

        for (String body : List.of("{\"amount\": 100.0}", "{\"amount\": \"100\"}", "{\"payment\": 17}")) {
            assertThat(refund(body))
                    .as(body)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("bad_request");
        }
        assertThat(refunds()).isZero();
    }

    @Test
    void replaysARetryByteForByte() {
        pay(10_000);
        String body = """
                {"amount": 2500}
                """;

        MvcTestResult first = refund(refunder, path(), "refund-1", body);
        MvcTestResult retry = refund(refunder, path(), "refund-1", body);

        assertThat(retry).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(refunds()).isOne();
    }

    @Test
    void replaysARefusal() {
        MvcTestResult first = refund(refunder, path(), "refund-1", "{}");
        pay(10_000);

        MvcTestResult retry = refund(refunder, path(), "refund-1", "{}");

        assertThat(retry).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(refunds()).isZero();
    }

    @Test
    void requiresTheIdempotencyKey() {
        pay(10_000);

        MvcTestResult result = send(mvc.post()
                .uri(path())
                .header("Authorization", "Bearer " + refunder.secret())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("idempotency_key_required");
        assertThat(refunds()).isZero();
    }

    @Test
    void requiresTheRefundsScope() {
        pay(10_000);

        MvcTestResult result = refund(issued(Environment.TEST, Permission.CHARGES_WRITE), path(), "refund-1", "{}");

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
        assertThat(refunds()).isZero();
    }

    @Test
    void refundsAPaymentExactlyTenTimesWhenTwentyRequestsOfATenthRaceForIt() throws Exception {
        pay(10_000);
        String body = """
                {"amount": 1000}
                """;

        List<MvcTestResult> results = new ArrayList<>();
        var start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(20)) {
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String idempotencyKey = "refund-" + i;
                futures.add(threads.submit(() -> {
                    start.await();
                    return refund(refunder, path(), idempotencyKey, body);
                }));
            }
            start.countDown();
            for (Future<MvcTestResult> future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results.stream().map(result -> result.getResponse().getStatus()))
                .containsOnly(HttpStatus.CREATED.value(), HttpStatus.UNPROCESSABLE_CONTENT.value())
                .filteredOn(status -> status == HttpStatus.CREATED.value())
                .hasSize(10);
        assertThat(results)
                .filteredOn(result -> result.getResponse().getStatus() == HttpStatus.UNPROCESSABLE_CONTENT.value())
                .allSatisfy(result ->
                        assertThat(body(result).path("code").asString()).isEqualTo("amount_exceeds_refundable"));
        assertThat(refunds()).isEqualTo(10);
        assertThat(read().status()).isEqualTo(ChargeStatus.REFUNDED);
    }

    private MvcTestResult refund(String body) {
        return refund(refunder, path(), "refund-" + UUID.randomUUID(), body);
    }

    private MvcTestResult refund(IssuedApiKey caller, String path, String idempotencyKey, String body) {
        return send(mvc.post()
                .uri(path)
                .header("Authorization", "Bearer " + caller.secret())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String path() {
        return "/v1/charges/" + charge.id().publicId() + "/refunds";
    }

    private MvcTestResult send(MockMvcTester.MockMvcRequestBuilder request) {
        MvcTestResult result = request.exchange();
        contract.assertConforms(result);
        return result;
    }

    private JsonNode body(MvcTestResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    /** Refused with the code, and nothing refunded. */
    private void assertRefused(MvcTestResult result, String code) {
        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo(code);
        assertThat(refunds()).isZero();
    }

    private static void assertNotFound(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
    }

    /** A credit of {@code minorUnits} for the charge, accepted by the rails now. */
    private PixCredit pay(long minorUnits) {
        var credit = new PixCredit(
                BillingFixtures.endToEndId(),
                "M99990011" + UUID.randomUUID().toString().replace("-", "").substring(0, 23),
                charge.transactionId(),
                brl(minorUnits),
                clock.instant(),
                "99990011");
        assertThat(settlement.handle(credit)).isEqualTo(CreditResult.RECORDED);
        return credit;
    }

    /** Moves part of the tenant's available balance to another of its accounts, as a payout's hold will. */
    private void hold(Money amount) {
        TestCaller.run(organization.test(), Environment.TEST, () -> {
            AccountId available = ledger.openAccount(account("available"));
            AccountId held = ledger.openAccount(account("held"));
            PostResult result = ledger.post(new EntryRequest(
                    organization.test().value(),
                    new EntrySource("test_hold", UUID.randomUUID().toString(), "hold"),
                    BusinessCalendar.businessDateOf(clock.instant()),
                    "req-hold",
                    List.of(PostingRequest.debit(available, amount), PostingRequest.credit(held, amount))));
            assertThat(result).isInstanceOf(PostResult.Posted.class);
        });
    }

    private AccountRequest account(String name) {
        return new AccountRequest(
                organization.test().value(),
                "tenant:" + organization.test().value() + ":" + name,
                AccountType.LIABILITY,
                Charge.CURRENCY,
                false,
                false);
    }

    private ChargeView read() {
        return TestCaller.call(organization.test(), Environment.TEST, () -> billing.charge(charge.id()))
                .orElseThrow()
                .charge();
    }

    private PaymentId paymentId(PixCredit credit) {
        return new PaymentId(owner.sql("SELECT id FROM billing.payments WHERE end_to_end_id = ?")
                .param(credit.endToEndId())
                .query(UUID.class)
                .single());
    }

    private long refunds() {
        return Objects.requireNonNull(owner.sql("SELECT count(*) FROM billing.refunds WHERE charge_id = ?")
                .param(charge.id().value())
                .query(Long.class)
                .single());
    }

    private IssuedApiKey issued(Environment environment, Permission scope) {
        return AccessFixtures.issue(
                access, AccessFixtures.admin(organization, environment), "Back office", Set.of(scope));
    }
}
