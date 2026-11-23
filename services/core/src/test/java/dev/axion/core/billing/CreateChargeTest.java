package dev.axion.core.billing;

import static dev.axion.core.billing.BillingFixtures.brl;
import static dev.axion.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.billing.application.BillingService;
import dev.axion.core.risk.RiskRule;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link BillingApi#createCharge} against a real PostgreSQL; each call commits. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class CreateChargeTest {

    // The test environment's limit of one charge, in centavos (application.yml).
    private static final long TEST_AMOUNT_LIMIT = 100_000;
    // The test environment's limit of charges in one clock hour (application.yml).
    private static final int TEST_HOURLY_LIMIT = 200;

    private final BillingApi billing;
    private final TenancyApi tenancy;
    private final JdbcClient owner;
    private final TransactionTemplate transaction;
    private final MeterRegistry meters;
    private final Clock clock;
    private final String testPixKey;
    private final String livePixKey;

    private NewOrganization organization;

    @Autowired
    CreateChargeTest(
            BillingApi billing,
            TenancyApi tenancy,
            DatabaseOwner databaseOwner,
            PlatformTransactionManager transactions,
            MeterRegistry meters,
            Clock clock,
            @Value("${finaxis.billing.pix-keys.test}") String testPixKey,
            @Value("${finaxis.billing.pix-keys.live}") String livePixKey) {
        this.billing = billing;
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
        this.transaction = new TransactionTemplate(transactions);
        this.meters = meters;
        this.clock = clock;
        this.testPixKey = testPixKey;
        this.livePixKey = livePixKey;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Padaria São João", "subject-" + UUID.randomUUID());
    }

    @Test
    void createsAnActiveChargeOfTheRequest() {
        var request = new ChargeRequest(
                brl(10_000), "Mensalidade de novembro", Duration.ofMinutes(30), Map.of("order_id", "ord_8842"));

        var charge = create(organization.test(), Environment.TEST, request);

        assertThat(charge.status()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(charge.amount()).isEqualTo(brl(10_000));
        assertThat(charge.amountRefunded()).isEqualTo(brl(0));
        assertThat(charge.description()).isEqualTo("Mensalidade de novembro");
        assertThat(charge.metadata()).containsExactlyEntriesOf(Map.of("order_id", "ord_8842"));
        assertThat(charge.expiresAt()).isEqualTo(charge.createdAt().plus(Duration.ofMinutes(30)));
        assertThat(charge.paidAt()).isNull();
        assertThat(charge.environment()).isEqualTo(Environment.TEST);
    }

    @Test
    void storesTheChargeForItsTenantAsItWasReturned() {
        var charge = create(
                organization.test(),
                Environment.TEST,
                new ChargeRequest(brl(10_000), null, Duration.ofMinutes(30), Map.of("order_id", "ord_8842")));

        var row = owner.sql("""
                        SELECT tenant_id, status, amount_minor, currency, amount_refunded, description,
                               metadata::text AS metadata, transaction_id, br_code, expires_at, paid_at, created_at,
                               updated_at
                          FROM billing.charges
                         WHERE id = ?
                        """).param(charge.id().value()).query().singleRow();

        assertThat(row.get("tenant_id")).isEqualTo(organization.test().value());
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("amount_minor")).isEqualTo(10_000L);
        assertThat(row.get("currency")).isEqualTo("BRL");
        assertThat(row.get("amount_refunded")).isEqualTo(0L);
        assertThat(row.get("description")).isNull();
        assertThat(row.get("metadata")).isEqualTo("{\"order_id\": \"ord_8842\"}");
        assertThat(row.get("transaction_id")).isEqualTo(charge.transactionId());
        assertThat(row.get("br_code")).isEqualTo(charge.brCode());
        assertThat(instant(row.get("expires_at"))).isEqualTo(charge.expiresAt());
        assertThat(row.get("paid_at")).isNull();
        assertThat(instant(row.get("created_at"))).isEqualTo(charge.createdAt());
        assertThat(instant(row.get("updated_at"))).isEqualTo(charge.createdAt());
    }

    @Test
    void encodesABrCodeOfTheChargeForTheTenantsOrganization() {
        var charge = create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));

        assertThat(charge.transactionId()).matches("[A-Za-z0-9]{25}");
        assertThat(charge.brCode())
                .startsWith("000201010212")
                .contains("0136" + testPixKey)
                .contains("5406100.00")
                .contains("5916Padaria Sao Joao")
                .contains("6009SAO PAULO")
                .contains("62290525" + charge.transactionId())
                .matches(".*6304[0-9A-F]{4}");
    }

    @Test
    void namesTheLiveKeyInALiveCharge() {
        var charge = create(organization.live(), Environment.LIVE, ChargeRequest.of(brl(10_000)));

        assertThat(charge.environment()).isEqualTo(Environment.LIVE);
        assertThat(charge.brCode()).contains("0136" + livePixKey).doesNotContain(testPixKey);
    }

    @Test
    void drawsADifferentTransactionIdForEachCharge() {
        var first = create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));
        var second = create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));

        assertThat(second.transactionId()).isNotEqualTo(first.transactionId());
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void opensTheTenantsAvailableAccountOnce() {
        create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));
        create(organization.test(), Environment.TEST, ChargeRequest.of(brl(5_000)));

        var accounts = owner.sql("""
                        SELECT code, type, currency, allow_negative, is_hot
                          FROM ledger.accounts
                         WHERE tenant_id = ?
                        """).param(organization.test().value()).query().listOfRows();

        assertThat(accounts)
                .containsExactly(Map.of(
                        "code",
                        "tenant:" + organization.test().value() + ":available",
                        "type",
                        "LIABILITY",
                        "currency",
                        "BRL",
                        "allow_negative",
                        false,
                        "is_hot",
                        false));
    }

    @Test
    void leavesNoChargeOrAccountWhenTheCallersTransactionRollsBack() {
        var charge = TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> transaction.execute(status -> {
                    var created = created(billing.createCharge(ChargeRequest.of(brl(10_000))));
                    status.setRollbackOnly();
                    return created;
                }));

        assertThat(count(
                        "SELECT count(*) FROM billing.charges WHERE id = ?",
                        charge.id().value()))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM ledger.accounts WHERE tenant_id = ?",
                        organization.test().value()))
                .isZero();
    }

    @Test
    void countsTheChargeInTheTenantsWindows() {
        create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));
        create(organization.test(), Environment.TEST, ChargeRequest.of(brl(5_000)));

        assertThat(windows(organization.test()))
                .containsExactlyInAnyOrder(
                        Map.of("rule", "DAILY_AMOUNT", "total_minor", 15_000L, "count", 2),
                        Map.of("rule", "HOURLY_COUNT", "total_minor", 15_000L, "count", 2));
    }

    @Test
    void deniesAChargeARiskRuleRefusesAndWritesNothing() {
        var result = TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> billing.createCharge(ChargeRequest.of(brl(TEST_AMOUNT_LIMIT + 1))));

        assertThat(result).isEqualTo(new CreateChargeResult.LimitExceeded(RiskRule.AMOUNT));
        assertThat(count(
                        "SELECT count(*) FROM billing.charges WHERE tenant_id = ?",
                        organization.test().value()))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM ledger.accounts WHERE tenant_id = ?",
                        organization.test().value()))
                .isZero();
    }

    @Test
    void createsAChargeAtTheAmountLimit() {
        var charge = create(organization.test(), Environment.TEST, ChargeRequest.of(brl(TEST_AMOUNT_LIMIT)));

        assertThat(charge.amount()).isEqualTo(brl(TEST_AMOUNT_LIMIT));
    }

    @Test
    void givesItsShareOfTheWindowsBackWhenTheCallersTransactionRollsBack() {
        create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));

        TenantContext.run(
                organization.test(),
                Environment.TEST,
                () -> transaction.executeWithoutResult(status -> {
                    billing.createCharge(ChargeRequest.of(brl(5_000)));
                    status.setRollbackOnly();
                }));

        assertThat(windows(organization.test()))
                .containsExactlyInAnyOrder(
                        Map.of("rule", "DAILY_AMOUNT", "total_minor", 10_000L, "count", 1),
                        Map.of("rule", "HOURLY_COUNT", "total_minor", 10_000L, "count", 1));
    }

    // The window of the next hour is filled too, so a run that crosses the hour finds the same room.
    @Test
    void createsExactlyTheChargesThereIsRoomForUnderConcurrentCalls() throws InterruptedException, ExecutionException {
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        for (Instant window : List.of(hour, hour.plus(Duration.ofHours(1)))) {
            owner.sql("""
                            INSERT INTO risk.charge_volume (tenant_id, rule, window_start, total_minor, count)
                            VALUES (?, 'HOURLY_COUNT', ?, 0, ?)
                            """)
                    .params(organization.test().value(), window.atOffset(ZoneOffset.UTC), TEST_HOURLY_LIMIT - 50)
                    .update();
        }
        var calls = new ArrayList<Callable<CreateChargeResult>>();
        for (int i = 0; i < 100; i++) {
            calls.add(() -> TenantContext.call(
                    organization.test(), Environment.TEST, () -> billing.createCharge(ChargeRequest.of(brl(100)))));
        }

        var results = atOnce(calls);

        assertThat(results)
                .filteredOn(CreateChargeResult.Created.class::isInstance)
                .hasSize(50);
        assertThat(results)
                .filteredOn(new CreateChargeResult.LimitExceeded(RiskRule.HOURLY_COUNT)::equals)
                .hasSize(50);
        assertThat(count(
                        "SELECT count(*) FROM billing.charges WHERE tenant_id = ?",
                        organization.test().value()))
                .isEqualTo(50);
    }

    @Test
    void countsTheChargesItCreated() {
        double before = chargesCreated();

        create(organization.test(), Environment.TEST, ChargeRequest.of(brl(10_000)));
        create(organization.live(), Environment.LIVE, ChargeRequest.of(brl(10_000)));

        assertThat(chargesCreated()).isEqualTo(before + 2);
    }

    @Test
    void countsNoChargeWhoseTransactionRolledBack() {
        double before = chargesCreated();

        TenantContext.run(
                organization.test(),
                Environment.TEST,
                () -> transaction.executeWithoutResult(status -> {
                    billing.createCharge(ChargeRequest.of(brl(10_000)));
                    status.setRollbackOnly();
                }));

        assertThat(chargesCreated()).isEqualTo(before);
    }

    @Test
    void refusesToCreateAChargeWithNoTenantBound() {
        assertThatThrownBy(() -> billing.createCharge(ChargeRequest.of(brl(10_000))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a tenant bound");
    }

    private ChargeView create(TenantId tenant, Environment environment, ChargeRequest request) {
        return TenantContext.call(tenant, environment, () -> created(billing.createCharge(request)));
    }

    private List<Map<String, @Nullable Object>> windows(TenantId tenant) {
        return owner.sql("SELECT rule, total_minor, count FROM risk.charge_volume WHERE tenant_id = ?")
                .param(tenant.value())
                .query()
                .listOfRows();
    }

    private double chargesCreated() {
        return meters.get(BillingService.CHARGES_CREATED).counter().count();
    }

    private long count(String sql, UUID id) {
        return owner.sql(sql).param(id).query(Long.class).single();
    }

    private static <T> List<T> atOnce(List<Callable<T>> calls) throws InterruptedException, ExecutionException {
        var ready = new CountDownLatch(calls.size());
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(calls.size())) {
            var futures = new ArrayList<Future<T>>();
            for (var call : calls) {
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            ready.await();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static Instant instant(@Nullable Object timestamp) {
        return ((Timestamp) Objects.requireNonNull(timestamp)).toInstant();
    }
}
