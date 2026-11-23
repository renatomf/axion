package dev.finaxis.core.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link RiskApi#assessCharge} with the limits of {@code application.yml} (billing design, section 6),
 * each call in a transaction of its own, as a charge's would be.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AssessChargeTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final long TEST_AMOUNT_LIMIT = 100_000;

    private final RiskApi risk;
    private final TransactionTemplate transaction;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    AssessChargeTest(RiskApi risk, PlatformTransactionManager transactions) {
        this.risk = risk;
        this.transaction = new TransactionTemplate(transactions);
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
}
