package dev.axion.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.idempotency.application.IdempotencyService;
import dev.axion.core.ledger.AccountRequest;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.LedgerApi;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link IdempotencyApi#execute} against a real PostgreSQL (idempotency design, section 4). The
 * operation's effect is an account opened in the ledger with a new code on every run, so only the key
 * can keep a second run from opening a second account.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ExecuteIdempotentlyTest {

    private static final byte[] BODY = "{\"id\":\"ch_1\"}".getBytes(StandardCharsets.UTF_8);
    private static final CapturedResponse CREATED =
            new CapturedResponse(201, "application/json", "/v1/charges/ch_1", BODY);

    private final IdempotencyApi idempotency;
    private final LedgerApi ledger;
    private final DatabaseOwner owner;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final IdempotencyKey key = new IdempotencyKey("order-" + UUID.randomUUID());
    private final Fingerprint fingerprint = Fingerprint.of("/v1/charges", BODY);
    private final AtomicInteger runs = new AtomicInteger();

    @Autowired
    ExecuteIdempotentlyTest(
            IdempotencyApi idempotency,
            LedgerApi ledger,
            DatabaseOwner owner,
            TransactionTemplate transactions,
            MeterRegistry meters) {
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.owner = owner;
        this.transactions = transactions;
        this.meters = meters;
    }

    @Test
    void runsTheOperationAndStoresItsResponseWithItsEffect() {
        var result = execute(request("first"), answering(CREATED));

        assertThat(result).isEqualTo(new IdempotentResult.Executed(CREATED));
        assertThat(storedKeys()).isOne();
        assertThat(effects()).isOne();
    }

    @Test
    void replaysACommittedKeyWithoutRunningTheOperationAgain() {
        execute(request("first"), answering(CREATED));

        var repeat = execute(request("second"), answering(CREATED));

        assertThat(repeat).isEqualTo(new IdempotentResult.Replayed(new StoredResponse(CREATED, "first")));
        assertThat(runs).hasValue(1);
        assertThat(effects()).isOne();
    }

    @Test
    void refusesTheKeyForAnotherRequest() {
        execute(request("first"), answering(CREATED));
        var other = new IdempotentRequest(tenant, key, Fingerprint.of("/v1/charges", new byte[] {'{', '}'}), "second");

        assertThat(execute(other, answering(CREATED))).isEqualTo(new IdempotentResult.KeyReused());
        assertThat(runs).hasValue(1);
    }

    @Test
    void answersInProgressWhileAnotherTransactionHoldsTheKey() {
        var holder = holdingTheKey();
        try {
            assertThat(execute(request("second"), answering(CREATED))).isEqualTo(new IdempotentResult.InProgress());
        } finally {
            holder.close();
        }
        assertThat(runs).hasValue(0);
        assertThat(storedKeys()).isZero();
    }

    @Test
    void keepsTheSameKeyOfAnotherTenantApart() {
        execute(request("first"), answering(CREATED));
        var otherTenant = new TenantId(UUID.randomUUID());

        var result = TenantContext.call(
                otherTenant,
                Environment.TEST,
                () -> idempotency.execute(
                        new IdempotentRequest(otherTenant, key, fingerprint, "second"),
                        () -> new CapturedResponse(201, "application/json", null, new byte[0])));

        assertThat(result).isInstanceOf(IdempotentResult.Executed.class);
    }

    @Test
    void storesAnExpectedRefusalAndReplaysIt() {
        var refusal = new CapturedResponse(422, "application/problem+json", null, BODY);
        execute(request("first"), answering(refusal));

        var repeat = execute(request("second"), answering(CREATED));

        assertThat(repeat).isEqualTo(new IdempotentResult.Replayed(new StoredResponse(refusal, "first")));
    }

    @Test
    void leavesNeitherTheKeyNorTheEffectWhenTheOperationThrows() {
        var failure = new IllegalStateException("operation failed");

        var thrown = catchThrowable(() -> execute(request("first"), () -> {
            openAccount();
            throw failure;
        }));

        assertThat(thrown).isSameAs(failure);
        assertThat(storedKeys()).isZero();
        assertThat(effects()).isZero();
    }

    @Test
    void runsAgainOnceAFailedRequestRolledBack() {
        assertThatIllegalStateException()
                .isThrownBy(() -> execute(request("first"), () -> {
                    openAccount();
                    throw new IllegalStateException("operation failed");
                }));

        assertThat(execute(request("second"), answering(CREATED))).isEqualTo(new IdempotentResult.Executed(CREATED));
        assertThat(effects()).isOne();
    }

    @Test
    void storesNoServerErrorAndRollsItsEffectBack() {
        var serverError = new CapturedResponse(503, "application/problem+json", null, BODY);

        var result = execute(request("first"), answering(serverError));

        assertThat(result).isEqualTo(new IdempotentResult.Executed(serverError));
        assertThat(storedKeys()).isZero();
        assertThat(effects()).isZero();
    }

    @Test
    void storesNothingWhenAModuleTheOperationCalledFailed() {
        var code = "test:" + UUID.randomUUID();
        TenantContext.run(tenant, Environment.TEST, () -> ledger.openAccount(account(code, AccountType.LIABILITY)));

        var result = execute(request("first"), () -> {
            openAccount();
            try {
                ledger.openAccount(account(code, AccountType.ASSET));
            } catch (IllegalStateException otherTerms) {
                // The ledger refused the other terms and marked the transaction rollback-only.
            }
            return CREATED;
        });

        assertThat(result).isEqualTo(new IdempotentResult.Executed(CREATED));
        assertThat(storedKeys()).isZero();
        // Only the account opened before the request.
        assertThat(effects()).isOne();
    }

    @Test
    void refusesToJoinATransactionAlreadyOpen() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.run(
                        tenant,
                        Environment.TEST,
                        () -> transactions.executeWithoutResult(
                                status -> idempotency.execute(request("first"), answering(CREATED)))));
        assertThat(runs).hasValue(0);
    }

    @Test
    void countsEachRequestByOutcome() {
        var before = outcomes();
        execute(request("first"), answering(CREATED));
        execute(request("second"), answering(CREATED));
        execute(new IdempotentRequest(tenant, key, Fingerprint.of("/v1/refunds", BODY), "third"), answering(CREATED));
        var holder = holdingTheKey();
        try {
            execute(request("fourth"), answering(CREATED));
        } finally {
            holder.close();
        }

        assertThat(outcomes().minus(before)).isEqualTo(new Outcomes(1, 1, 1, 1));
    }

    private IdempotentResult execute(IdempotentRequest request, Supplier<CapturedResponse> operation) {
        return TenantContext.call(tenant, Environment.TEST, () -> idempotency.execute(request, operation));
    }

    /** Another transaction holding the key's lock until the session closes. */
    private DatabaseOwner.Session holdingTheKey() {
        var holder = owner.session();
        holder.jdbc()
                .sql("SELECT pg_advisory_xact_lock(hashtext('idempotency.keys'), hashtext(? || ':' || ?))")
                .params(tenant.value().toString(), key.value())
                .query()
                .singleRow();
        return holder;
    }

    private IdempotentRequest request(String correlationId) {
        return new IdempotentRequest(tenant, key, fingerprint, correlationId);
    }

    /** An operation that opens an account, then answers the response. */
    private Supplier<CapturedResponse> answering(CapturedResponse response) {
        return () -> {
            openAccount();
            return response;
        };
    }

    private void openAccount() {
        runs.incrementAndGet();
        ledger.openAccount(account("test:" + UUID.randomUUID(), AccountType.LIABILITY));
    }

    private AccountRequest account(String code, AccountType type) {
        return new AccountRequest(tenant.value(), code, type, Currency.getInstance("BRL"), false, false);
    }

    private long storedKeys() {
        return count("SELECT count(*) FROM idempotency.keys WHERE tenant_id = ?");
    }

    // Each run of the operation opens one account of the tenant.
    private long effects() {
        return count("SELECT count(*) FROM ledger.accounts WHERE tenant_id = ?");
    }

    private long count(String sql) {
        return Objects.requireNonNull(
                owner.jdbc().sql(sql).param(tenant.value()).query(Long.class).single());
    }

    private Outcomes outcomes() {
        return new Outcomes(outcome("executed"), outcome("replayed"), outcome("in_progress"), outcome("key_reused"));
    }

    private long outcome(String outcome) {
        return (long) meters.get(IdempotencyService.REQUESTS)
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private record Outcomes(long executed, long replayed, long inProgress, long keyReused) {

        Outcomes minus(Outcomes other) {
            return new Outcomes(
                    executed - other.executed,
                    replayed - other.replayed,
                    inProgress - other.inProgress,
                    keyReused - other.keyReused);
        }
    }
}
