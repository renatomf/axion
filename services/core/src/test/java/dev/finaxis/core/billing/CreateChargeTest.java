package dev.finaxis.core.billing;

import static dev.finaxis.core.billing.BillingFixtures.brl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

    private final BillingApi billing;
    private final TenancyApi tenancy;
    private final JdbcClient owner;
    private final TransactionTemplate transaction;
    private final String testPixKey;
    private final String livePixKey;

    private NewOrganization organization;

    @Autowired
    CreateChargeTest(
            BillingApi billing,
            TenancyApi tenancy,
            DatabaseOwner databaseOwner,
            PlatformTransactionManager transactions,
            @Value("${finaxis.billing.pix-keys.test}") String testPixKey,
            @Value("${finaxis.billing.pix-keys.live}") String livePixKey) {
        this.billing = billing;
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
        this.transaction = new TransactionTemplate(transactions);
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
    void refusesToCreateAChargeWithNoTenantBound() {
        assertThatThrownBy(() -> billing.createCharge(ChargeRequest.of(brl(10_000))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a tenant bound");
    }

    private ChargeView create(TenantId tenant, Environment environment, ChargeRequest request) {
        return TenantContext.call(tenant, environment, () -> created(billing.createCharge(request)));
    }

    private static ChargeView created(CreateChargeResult result) {
        return switch (result) {
            case CreateChargeResult.Created created -> created.charge();
        };
    }

    private long count(String sql, UUID id) {
        return owner.sql(sql).param(id).query(Long.class).single();
    }

    private static Instant instant(@Nullable Object timestamp) {
        return ((Timestamp) Objects.requireNonNull(timestamp)).toInstant();
    }
}
