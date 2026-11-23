package dev.axion.core.idempotency.web;

import static dev.axion.core.tenancy.Permission.CHARGES_READ;
import static dev.axion.core.tenancy.Permission.CHARGES_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.access.AccessApi;
import dev.axion.core.access.AccessFixtures;
import dev.axion.core.access.IssuedApiKey;
import dev.axion.core.access.RequiresPermission;
import dev.axion.core.access.TenantBoundAccess;
import dev.axion.core.ledger.AccountRequest;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.LedgerApi;
import dev.axion.core.observability.CorrelationIdFilter;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.TenancyApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The idempotency step of {@code /v1} writes over HTTP (idempotency design, sections 3 and 4.3),
 * against an endpoint that exists only in this test. Its effect is an account opened in the ledger
 * with a new code on every run, so only the key can keep a repeat from opening a second account.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, IdempotencyStepTest.WriteController.class})
@AutoConfigureMockMvc
class IdempotencyStepTest {

    static final String PATH = "/v1/idempotent_writes";

    /** Over it, the write is refused, as an operation refuses what a limit does not allow. */
    static final int LIMIT = 1_000;

    /** The amount whose write fails after its effect, as a bug would. */
    static final int FAILING = 500;

    record WriteRequest(@Positive int amount) {}

    @RestController
    static class WriteController {

        private final LedgerApi ledger;

        WriteController(LedgerApi ledger) {
            this.ledger = ledger;
        }

        @RequiresPermission(CHARGES_WRITE)
        @PostMapping(PATH)
        ResponseEntity<Map<String, Object>> write(@Valid @RequestBody WriteRequest request) {
            if (request.amount() > LIMIT) {
                ProblemDetail problem =
                        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "Over the limit.");
                problem.setProperty("code", "limit_exceeded");
                throw new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, problem, null);
            }
            TenantId tenant = TenantContext.current().orElseThrow().tenant();
            String code = "test:" + UUID.randomUUID();
            ledger.openAccount(new AccountRequest(
                    tenant.value(), code, AccountType.LIABILITY, Currency.getInstance("BRL"), false, false));
            if (request.amount() == FAILING) {
                throw new IllegalStateException("failed after its effect");
            }
            return ResponseEntity.created(URI.create(PATH + "/" + code))
                    .body(Map.of("code", code, "amount", request.amount()));
        }

        /** Answers with the length of the body it received. */
        @RequiresPermission(CHARGES_WRITE)
        @PostMapping(PATH + "/echo")
        String echo(@RequestBody byte[] body) {
            return Integer.toString(body.length);
        }

        @RequiresPermission(CHARGES_WRITE)
        @GetMapping(PATH)
        String read() {
            return "read";
        }
    }

    private static final String BODY = "{\"amount\": 100}";

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final DatabaseOwner owner;
    private NewOrganization organization;
    private IssuedApiKey key;

    @Autowired
    IdempotencyStepTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy, DatabaseOwner owner) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = owner;
    }

    @BeforeEach
    void issueKey() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
        key = issued(CHARGES_WRITE);
    }

    @Test
    void requiresTheKeyOnAWrite() {
        var withoutKey = mvc.post()
                .uri(PATH)
                .header("Authorization", "Bearer " + key.secret())
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);

        assertProblem(assertThat(withoutKey), HttpStatus.BAD_REQUEST, "idempotency_key_required");
    }

    @Test
    void refusesAMalformedKey() {
        for (String malformed : new String[] {"", "with space", "chave-é", "x".repeat(256)}) {
            assertProblem(assertThat(write(key, malformed, BODY)), HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
        }
    }

    @Test
    void refusesTwoKeys() {
        var twoKeys = mvc.post()
                .uri(PATH)
                .header("Authorization", "Bearer " + key.secret())
                .header(IdempotencyStep.HEADER, "first", "second")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);

        assertProblem(assertThat(twoKeys), HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
    }

    @Test
    void acceptsAKeyOfVisibleAscii() {
        assertThat(write(key, "!" + "~".repeat(254), BODY)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void passesABodyOfAtMost64KibToTheController() {
        echo(new byte[IdempotencyStep.MAX_BODY]).hasStatusOk().hasBodyTextEqualTo("65536");
    }

    @Test
    void refusesABodyOver64Kib() {
        assertProblem(echo(new byte[IdempotencyStep.MAX_BODY + 1]), HttpStatus.CONTENT_TOO_LARGE, "content_too_large");
        assertThat(storedKeys()).isZero();
    }

    @Test
    void requiresNoKeyOnARead() {
        assertThat(mvc.get().uri(PATH).header("Authorization", "Bearer " + key.secret()))
                .hasStatusOk()
                .hasBodyTextEqualTo("read");
    }

    @Test
    void checksTheScopeBeforeTheKey() {
        var withoutKey = mvc.post()
                .uri(PATH)
                .header("Authorization", "Bearer " + issued(CHARGES_READ).secret());

        assertProblem(assertThat(withoutKey), HttpStatus.FORBIDDEN, "insufficient_permission");
    }

    @Test
    void runsAWriteOnceAndAnswersItsResponse() {
        MvcTestResult first = write(key, "order-1", BODY);

        assertThat(first)
                .hasStatus(HttpStatus.CREATED)
                .hasContentType(MediaType.APPLICATION_JSON)
                .doesNotContainHeader(IdempotencyStep.REPLAYED_HEADER)
                .bodyJson()
                .extractingPath("$.amount")
                .isEqualTo(100);
        assertThat(first.getResponse().getHeader("Location")).startsWith(PATH + "/test:");
        assertThat(effects()).isOne();
        assertThat(storedKeys()).isOne();
    }

    @Test
    void replaysARepeatByteForByteWithItsOwnCorrelationId() {
        MvcTestResult first = write(key, "order-1", BODY);

        MvcTestResult repeat = mvc.post()
                .uri(PATH)
                .header("Authorization", "Bearer " + key.secret())
                .header(IdempotencyStep.HEADER, "order-1")
                .header(CorrelationIdFilter.HEADER, "the-retry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .exchange();

        assertThat(repeat)
                .hasStatus(HttpStatus.CREATED)
                .hasContentType(MediaType.APPLICATION_JSON)
                .hasHeader(IdempotencyStep.REPLAYED_HEADER, "true")
                .hasHeader(CorrelationIdFilter.HEADER, "the-retry")
                .hasHeader(
                        "Location", Objects.requireNonNull(first.getResponse().getHeader("Location")))
                .containsHeader("RateLimit");
        assertThat(repeat.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(effects()).isOne();
    }

    @Test
    void replaysToAnotherKeyOfTheTenantWithTheScope() {
        write(key, "order-1", BODY);

        assertThat(write(issued(CHARGES_WRITE), "order-1", BODY))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader(IdempotencyStep.REPLAYED_HEADER, "true");
        assertThat(effects()).isOne();
    }

    @Test
    void refusesTheKeyForAnotherBodyOrPath() {
        write(key, "order-1", BODY);

        assertProblem(
                assertThat(write(key, "order-1", "{\"amount\":100}")),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency_key_reused");
        assertProblem(
                assertThat(mvc.post()
                        .uri(PATH + "/echo")
                        .header("Authorization", "Bearer " + key.secret())
                        .header(IdempotencyStep.HEADER, "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency_key_reused");
        assertThat(effects()).isOne();
    }

    @Test
    void answersInProgressWhileARequestWithTheKeyRuns() {
        var holder = holding("order-1");
        try {
            assertProblem(assertThat(write(key, "order-1", BODY)), HttpStatus.CONFLICT, "request_in_progress")
                    .hasHeader("Retry-After", "1");
        } finally {
            holder.close();
        }
        assertThat(effects()).isZero();
    }

    @Test
    void storesNoRejectedBodySoTheFixedRequestRuns() {
        assertProblem(
                assertThat(write(key, "order-1", "{\"amount\": -1}")),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "validation_failed");
        assertThat(storedKeys()).isZero();

        assertThat(write(key, "order-1", BODY)).hasStatus(HttpStatus.CREATED);
        assertThat(effects()).isOne();
    }

    @Test
    void storesAThrownRefusalAndReplaysIt() {
        String overLimit = "{\"amount\": " + (LIMIT + 1) + "}";
        assertProblem(assertThat(write(key, "order-1", overLimit)), HttpStatus.UNPROCESSABLE_CONTENT, "limit_exceeded")
                .doesNotContainHeader(IdempotencyStep.REPLAYED_HEADER);

        assertProblem(assertThat(write(key, "order-1", overLimit)), HttpStatus.UNPROCESSABLE_CONTENT, "limit_exceeded")
                .hasHeader(IdempotencyStep.REPLAYED_HEADER, "true");
        assertThat(storedKeys()).isOne();
    }

    @Test
    void storesNoServerErrorAndRollsItsEffectBack() {
        String failing = "{\"amount\": " + FAILING + "}";

        assertProblem(
                assertThat(write(key, "order-1", failing)), HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error");

        assertThat(effects()).isZero();
        assertThat(storedKeys()).isZero();
    }

    @Test
    void givesNoReplayToAKeyWithoutTheScope() {
        write(key, "order-1", BODY);

        assertProblem(
                assertThat(write(issued(CHARGES_READ), "order-1", BODY)),
                HttpStatus.FORBIDDEN,
                "insufficient_permission");
    }

    @Test
    void givesNoReplayToARevokedKey() {
        write(key, "order-1", BODY);
        access.revoke(AccessFixtures.admin(organization, Environment.TEST), key.id());

        assertProblem(assertThat(write(key, "order-1", BODY)), HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    private MvcTestResult write(IssuedApiKey caller, String idempotencyKey, String body) {
        return mvc.post()
                .uri(PATH)
                .header("Authorization", "Bearer " + caller.secret())
                .header(IdempotencyStep.HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResultAssert echo(byte[] body) {
        return assertThat(mvc.post()
                .uri(PATH + "/echo")
                .header("Authorization", "Bearer " + key.secret())
                .header(IdempotencyStep.HEADER, "order-1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body));
    }

    private static MvcTestResultAssert assertProblem(MvcTestResultAssert result, HttpStatus status, String code) {
        result.hasStatus(status)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo(code);
        return result;
    }

    /** Another transaction holding the lock of the key, as a request with it that is still running. */
    private DatabaseOwner.Session holding(String idempotencyKey) {
        var holder = owner.session();
        holder.jdbc()
                .sql("SELECT pg_advisory_xact_lock(hashtext('idempotency.keys'), hashtext(? || ':' || ?))")
                .params(organization.test().value().toString(), idempotencyKey)
                .query()
                .singleRow();
        return holder;
    }

    private IssuedApiKey issued(Permission scope) {
        return AccessFixtures.issue(
                access, AccessFixtures.admin(organization, Environment.TEST), "Checkout", Set.of(scope));
    }

    private long storedKeys() {
        return count("SELECT count(*) FROM idempotency.keys WHERE tenant_id = ?");
    }

    // Each run of the write opens one account of the tenant.
    private long effects() {
        return count("SELECT count(*) FROM ledger.accounts WHERE tenant_id = ?");
    }

    private long count(String sql) {
        return Objects.requireNonNull(owner.jdbc()
                .sql(sql)
                .param(organization.test().value())
                .query(Long.class)
                .single());
    }
}
