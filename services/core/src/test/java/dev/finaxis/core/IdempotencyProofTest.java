package dev.finaxis.core;

import static dev.finaxis.core.tenancy.Permission.CHARGES_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.ApiKeyCaller;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.access.TenantBoundAccess;
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
import dev.finaxis.core.shared.BusinessCalendar;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code financial-integrity} proof of idempotency (idempotency design, section 8): repeats of
 * one request, sent at once over HTTP to a running server, move money once. The endpoint exists only
 * in this test; its effect is a ledger entry with a new source ID on every run, so the ledger's own
 * idempotency cannot hide a duplicate and only the key can prevent the second entry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, IdempotencyProofTest.PaymentController.class})
class IdempotencyProofTest {

    static final String PATH = "/v1/proof_payments";

    private static final Currency BRL = Currency.getInstance("BRL");

    /** Fifty is also the burst of a test key, so no repeat is rate limited. */
    private static final int REPEATS = 50;

    record PaymentRequest(UUID debit, UUID credit, long amount) {}

    /** Moves the amount between two accounts of the caller's tenant, as a new entry on every run. */
    @RestController
    static class PaymentController {

        private final LedgerApi ledger;
        private final Clock clock;

        PaymentController(LedgerApi ledger, Clock clock) {
            this.ledger = ledger;
            this.clock = clock;
        }

        @RequiresPermission(CHARGES_WRITE)
        @PostMapping(PATH)
        ResponseEntity<Map<String, Object>> pay(ApiKeyCaller caller, @RequestBody PaymentRequest request) {
            String source = "pay_" + UUID.randomUUID();
            Money amount = Money.ofMinor(request.amount(), BRL);
            PostResult result = ledger.post(new EntryRequest(
                    caller.tenant().value(),
                    new EntrySource("proof", source, "payment"),
                    BusinessCalendar.today(clock),
                    "proof",
                    List.of(
                            PostingRequest.debit(new AccountId(request.debit()), amount),
                            PostingRequest.credit(new AccountId(request.credit()), amount))));
            if (!(result instanceof PostResult.Posted)) {
                throw new IllegalStateException("The payment was not posted: " + result);
            }
            return ResponseEntity.created(URI.create(PATH + "/" + source))
                    .body(Map.of("source", source, "amount", request.amount()));
        }
    }

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final URI uri;
    private final LedgerApi ledger;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient owner;
    private final BalanceVerifier verifier;

    private NewOrganization organization;
    private IssuedApiKey key;
    private AccountId settlement;
    private AccountId available;

    @Autowired
    IdempotencyProofTest(
            @LocalServerPort int port,
            LedgerApi ledger,
            AccessApi access,
            TenancyApi tenancy,
            DatabaseOwner owner,
            BalanceVerifier verifier) {
        this.uri = URI.create("http://localhost:" + port + PATH);
        this.ledger = ledger;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = owner.jdbc();
        this.verifier = verifier;
    }

    @BeforeEach
    void openAccounts() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
        key = AccessFixtures.issue(
                access, AccessFixtures.admin(organization, Environment.TEST), "Checkout", Set.of(CHARGES_WRITE));
        settlement = open("tenant:settlement", AccountType.ASSET, true);
        available = open("tenant:available", AccountType.LIABILITY, false);
    }

    /** What a test commits must leave the ledger's invariants intact (ledger design, section 8). */
    @AfterEach
    void leavesTheLedgerSound() {
        assertThat(verifier.verify()).isEqualTo(new LedgerVerification(List.of(), List.of(), List.of()));
    }

    @Test
    void movesMoneyOnceForParallelRepeatsOfOneKey() throws Exception {
        String body = payment(1_000);

        var answers = sendAtOnce(REPEATS, () -> pay("order-1", body));

        // Each repeat either ran the payment, found it running (409) or found it done (the replay).
        assertThat(answers).extracting(HttpResponse::statusCode).containsOnly(201, 409);
        var created =
                answers.stream().filter(answer -> answer.statusCode() == 201).toList();
        assertThat(created)
                .filteredOn(answer ->
                        answer.headers().firstValue("Idempotent-Replayed").isEmpty())
                .hasSize(1);
        assertThat(created)
                .extracting(HttpResponse::body)
                .containsOnly(created.getFirst().body());
        assertThat(answers)
                .filteredOn(answer -> answer.statusCode() == 409)
                .allSatisfy(answer -> assertThat(code(answer)).isEqualTo("request_in_progress"));

        assertThat(entries()).isOne();
        assertThat(storedBalance(available)).isEqualTo(1_000);
    }

    /**
     * The control for the test above: the same payment under two keys is two payments. So one entry
     * there shows the key at work, not an endpoint that could not post twice.
     */
    @Test
    void movesMoneyAgainForAnotherKey() throws Exception {
        String body = payment(1_000);

        assertThat(pay("order-1", body).statusCode()).isEqualTo(201);
        assertThat(pay("order-2", body).statusCode()).isEqualTo(201);

        assertThat(entries()).isEqualTo(2);
        assertThat(storedBalance(available)).isEqualTo(2_000);
    }

    private String payment(long amount) {
        return """
                {"debit": "%s", "credit": "%s", "amount": %d}""".formatted(settlement.value(), available.value(), amount);
    }

    private HttpResponse<String> pay(String idempotencyKey, String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + key.secret())
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Sends every request together, each on its own thread. */
    private static List<HttpResponse<String>> sendAtOnce(int count, Callable<HttpResponse<String>> call)
            throws InterruptedException, ExecutionException {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < count; i++) {
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            ready.await();
            start.countDown();
            var answers = new ArrayList<HttpResponse<String>>();
            for (var future : futures) {
                answers.add(future.get());
            }
            return answers;
        }
    }

    private static String code(HttpResponse<String> problem) {
        return JsonPath.read(problem.body(), "$.code");
    }

    private AccountId open(String code, AccountType type, boolean allowNegative) {
        return TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> ledger.openAccount(
                        new AccountRequest(organization.test().value(), code, type, BRL, allowNegative, false)));
    }

    /** Read as the owner, so Row-Level Security cannot hide an entry that was posted. */
    private long entries() {
        return Objects.requireNonNull(owner.sql("SELECT count(*) FROM ledger.entries WHERE tenant_id = ?")
                .param(organization.test().value())
                .query(Long.class)
                .single());
    }

    private long storedBalance(AccountId account) {
        return Objects.requireNonNull(owner.sql("SELECT balance FROM ledger.balances WHERE account_id = ?")
                .param(account.value())
                .query(Long.class)
                .single());
    }
}
