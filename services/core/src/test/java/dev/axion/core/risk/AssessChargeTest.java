package dev.axion.core.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.risk.application.RiskService;
import dev.axion.core.shared.BusinessCalendar;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link RiskApi#assessCharge} with the limits of {@code application.yml} (billing design, section 6),
 * each call in a transaction of its own, as a charge's would be. The test environment allows R$ 1,000.00
 * per charge, R$ 10,000.00 per business date and 200 charges per clock hour. The clock stands still, so
 * a window changes only when a test moves it; each test has a tenant of its own.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, AssessChargeTest.Configuration.class})
class AssessChargeTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final long TEST_AMOUNT_LIMIT = 100_000;
    private static final long TEST_DAILY_LIMIT = 1_000_000;
    private static final int TEST_HOURLY_LIMIT = 200;

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-20T12:30:00Z"));
        }
    }

    private final RiskApi risk;
    private final TransactionTemplate transaction;
    private final JdbcClient owner;
    private final MutableClock clock;
    private final MeterRegistry meters;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    AssessChargeTest(
            RiskApi risk,
            PlatformTransactionManager transactions,
            DatabaseOwner databaseOwner,
            MutableClock clock,
            MeterRegistry meters) {
        this.risk = risk;
        this.transaction = new TransactionTemplate(transactions);
        this.owner = databaseOwner.jdbc();
        this.clock = clock;
        this.meters = meters;
    }

    @Test
    void allowsACharge() {
        assertThat(assess(Environment.TEST, 10_000)).isEqualTo(new ChargeDecision.Allowed());
    }

    @Test
    void allowsAChargeAtTheAmountLimit() {
        assertThat(assess(Environment.TEST, TEST_AMOUNT_LIMIT)).isEqualTo(new ChargeDecision.Allowed());
    }

    @Test
    void deniesAChargeOneCentavoPastTheAmountLimit() {
        assertThat(assess(Environment.TEST, TEST_AMOUNT_LIMIT + 1))
                .isEqualTo(new ChargeDecision.Denied(RiskRule.AMOUNT));
    }

    @Test
    void appliesTheLimitsOfTheBoundEnvironment() {
        assertThat(assess(Environment.LIVE, TEST_AMOUNT_LIMIT + 1)).isEqualTo(new ChargeDecision.Allowed());
    }

    @Test
    void countsNothingForAChargeDeniedByItsAmount() {
        assess(Environment.TEST, TEST_AMOUNT_LIMIT + 1);

        assertThat(windowsOfTheTenant()).isZero();
    }

    @Test
    void allowsChargesUpToTheDailyAmountAndDeniesOneCentavoPastIt() {
        for (int i = 0; i < TEST_DAILY_LIMIT / TEST_AMOUNT_LIMIT; i++) {
            assertThat(assess(Environment.TEST, TEST_AMOUNT_LIMIT)).isEqualTo(new ChargeDecision.Allowed());
        }

        assertThat(assess(Environment.TEST, 1)).isEqualTo(new ChargeDecision.Denied(RiskRule.DAILY_AMOUNT));
        assertThat(total(RiskRule.DAILY_AMOUNT)).isEqualTo(TEST_DAILY_LIMIT);
    }

    @Test
    void allowsChargesUpToTheHourlyCountAndDeniesOneMore() {
        assessTimes(TEST_HOURLY_LIMIT, 1);

        assertThat(assess(Environment.TEST, 1)).isEqualTo(new ChargeDecision.Denied(RiskRule.HOURLY_COUNT));
        assertThat(count(RiskRule.HOURLY_COUNT)).isEqualTo(TEST_HOURLY_LIMIT);
    }

    @Test
    void leavesEveryWindowAsItWasWhenALaterWindowDenies() {
        assessTimes(TEST_HOURLY_LIMIT, 1);

        assess(Environment.TEST, 500);

        assertThat(total(RiskRule.DAILY_AMOUNT)).isEqualTo(TEST_HOURLY_LIMIT);
        assertThat(count(RiskRule.DAILY_AMOUNT)).isEqualTo(TEST_HOURLY_LIMIT);
    }

    @Test
    void startsANewCountEachClockHour() {
        assessTimes(TEST_HOURLY_LIMIT, 1);

        clock.advance(Duration.between(
                clock.instant(), clock.instant().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1))));

        assertThat(assess(Environment.TEST, 1)).isEqualTo(new ChargeDecision.Allowed());
    }

    // 23:59 in São Paulo is 02:59 UTC of the next calendar day, and still the same business date.
    @Test
    void startsANewDailyAmountAtMidnightInSaoPaulo() {
        Instant midnight = BusinessCalendar.today(clock)
                .plusDays(1)
                .atStartOfDay(BusinessCalendar.ZONE)
                .toInstant();
        clock.advance(Duration.between(clock.instant(), midnight.minus(Duration.ofMinutes(1))));
        for (int i = 0; i < TEST_DAILY_LIMIT / TEST_AMOUNT_LIMIT; i++) {
            assess(Environment.TEST, TEST_AMOUNT_LIMIT);
        }
        assertThat(assess(Environment.TEST, 1)).isEqualTo(new ChargeDecision.Denied(RiskRule.DAILY_AMOUNT));

        clock.advance(Duration.ofMinutes(1));

        assertThat(assess(Environment.TEST, 1)).isEqualTo(new ChargeDecision.Allowed());
    }

    @Test
    void givesBackTheShareOfAChargeRolledBack() {
        TenantContext.run(
                tenant,
                Environment.TEST,
                () -> transaction.executeWithoutResult(status -> {
                    risk.assessCharge(tenant, Money.ofMinor(10_000, BRL));
                    status.setRollbackOnly();
                }));

        assertThat(windowsOfTheTenant()).isZero();
    }

    @Test
    void allowsExactlyTheChargesThereIsRoomForUnderConcurrentCalls() throws InterruptedException, ExecutionException {
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        owner.sql("""
                        INSERT INTO risk.charge_volume (tenant_id, rule, window_start, total_minor, count)
                        VALUES (?, 'HOURLY_COUNT', ?, 0, ?)
                        """)
                .params(tenant.value(), hour.atOffset(ZoneOffset.UTC), TEST_HOURLY_LIMIT - 50)
                .update();
        var charges = new ArrayList<Callable<ChargeDecision>>();
        for (int i = 0; i < 100; i++) {
            charges.add(() -> assess(Environment.TEST, 100));
        }

        var decisions = atOnce(charges);

        assertThat(decisions)
                .filteredOn(ChargeDecision.Allowed.class::isInstance)
                .hasSize(50);
        assertThat(decisions)
                .filteredOn(new ChargeDecision.Denied(RiskRule.HOURLY_COUNT)::equals)
                .hasSize(50);
        assertThat(count(RiskRule.HOURLY_COUNT)).isEqualTo(TEST_HOURLY_LIMIT);
        assertThat(total(RiskRule.DAILY_AMOUNT)).isEqualTo(50 * 100);
    }

    @Test
    void countsEachDenialByItsRule() {
        double amountBefore = denials("amount");
        double hourlyBefore = denials("hourly_count");
        assess(Environment.TEST, TEST_AMOUNT_LIMIT + 1);
        assessTimes(TEST_HOURLY_LIMIT + 2, 1);

        assertThat(denials("amount")).isEqualTo(amountBefore + 1);
        assertThat(denials("hourly_count")).isEqualTo(hourlyBefore + 2);
    }

    @Test
    void registersADenialCounterForEveryRuleAtStartup() {
        assertThat(meters.get(RiskService.DENIALS).counters()).hasSize(RiskRule.values().length);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100})
    void refusesAnAmountThatIsNotPositive(long amount) {
        assertThatThrownBy(() -> assess(Environment.TEST, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive, in BRL");
    }

    @Test
    void refusesAnAmountInAnotherCurrency() {
        var dollars = Money.ofMinor(10_000, Currency.getInstance("USD"));

        assertThatThrownBy(() -> TenantContext.call(
                        tenant,
                        Environment.TEST,
                        () -> transaction.execute(status -> risk.assessCharge(tenant, dollars))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive, in BRL");
    }

    @Test
    void refusesToAssessOutsideATransaction() {
        assertThatThrownBy(() -> TenantContext.call(
                        tenant, Environment.TEST, () -> risk.assessCharge(tenant, Money.ofMinor(10_000, BRL))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void refusesToAssessWithNoTenantBound() {
        assertThatThrownBy(() -> transaction.execute(status -> risk.assessCharge(tenant, Money.ofMinor(10_000, BRL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a tenant bound");
    }

    @Test
    void refusesToAssessForATenantOtherThanTheBoundOne() {
        var other = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> TenantContext.call(
                        other,
                        Environment.TEST,
                        () -> transaction.execute(status -> risk.assessCharge(tenant, Money.ofMinor(10_000, BRL)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("other than the one bound");
    }

    private ChargeDecision assess(Environment environment, long amount) {
        return TenantContext.call(
                tenant,
                environment,
                () -> transaction.execute(status -> risk.assessCharge(tenant, Money.ofMinor(amount, BRL))));
    }

    private double denials(String rule) {
        return meters.get(RiskService.DENIALS).tag("rule", rule).counter().count();
    }

    private void assessTimes(int times, long amount) {
        for (int i = 0; i < times; i++) {
            assess(Environment.TEST, amount);
        }
    }

    private long windowsOfTheTenant() {
        return owner.sql("SELECT count(*) FROM risk.charge_volume WHERE tenant_id = ?")
                .param(tenant.value())
                .query(Long.class)
                .single();
    }

    // The window of the rule that the clock is in.
    private long total(RiskRule rule) {
        return column("total_minor", rule);
    }

    private long count(RiskRule rule) {
        return column("count", rule);
    }

    private long column(String column, RiskRule rule) {
        return owner.sql("SELECT " + column + " FROM risk.charge_volume WHERE tenant_id = ? AND rule = ?"
                        + " ORDER BY window_start DESC LIMIT 1")
                .params(tenant.value(), rule.name())
                .query(Long.class)
                .single();
    }

    /** Starts every call together, each on its own thread. */
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
}
