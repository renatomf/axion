package dev.finaxis.core.billing.application;

import static dev.finaxis.core.billing.BillingFixtures.brl;
import static dev.finaxis.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.ModuleRoles;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.billing.ChargeRequest;
import dev.finaxis.core.billing.ChargeView;
import dev.finaxis.core.billing.domain.TransactionId;
import dev.finaxis.core.billing.persistence.JdbcCharges;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.risk.RiskApi;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.Serial;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A transaction ID already taken, by a charge of any tenant, is drawn again (billing design, section
 * 5.2). The random source is scripted, so the collisions are certain.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TransactionIdDrawTest {

    private final JdbcCharges charges;
    private final LedgerApi ledger;
    private final RiskApi risk;
    private final TenancyApi tenancy;
    private final ModuleRoles roles;
    private final BillingProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;

    private NewOrganization first;
    private NewOrganization second;

    @Autowired
    TransactionIdDrawTest(
            JdbcCharges charges,
            LedgerApi ledger,
            RiskApi risk,
            TenancyApi tenancy,
            ModuleRoles roles,
            BillingProperties properties,
            Clock clock,
            MeterRegistry meters,
            PlatformTransactionManager transactions) {
        this.charges = charges;
        this.ledger = ledger;
        this.risk = risk;
        this.tenancy = tenancy;
        this.roles = roles;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.transaction = new TransactionTemplate(transactions);
    }

    @BeforeEach
    void createOrganizations() {
        first = tenancy.createOrganization("Acme", "subject-" + UUID.randomUUID());
        second = tenancy.createOrganization("Globex", "subject-" + UUID.randomUUID());
    }

    @Test
    void drawsAgainWhenAnotherTenantsChargeHoldsTheTransactionId() {
        var taken = newTransactionId();
        var free = newTransactionId();
        var billing = billing(taken, taken, free);
        var held = create(billing, first);

        var charge = create(billing, second);

        assertThat(held.transactionId()).isEqualTo(taken);
        assertThat(charge.transactionId()).isEqualTo(free);
        assertThat(charge.brCode()).contains(free).doesNotContain(taken);
    }

    @Test
    void failsAfterTheLastDrawFindsItTaken() {
        var taken = newTransactionId();
        var billing = billing(taken, taken, taken, taken);
        create(billing, first);

        assertThatThrownBy(() -> create(billing, second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No free transaction ID in 3 draws");
    }

    private BillingService billing(String... transactionIds) {
        return new BillingService(
                charges,
                ledger,
                risk,
                tenancy,
                roles,
                properties,
                clock,
                meters,
                new ScriptedRandom(List.of(transactionIds)));
    }

    // The service is not a Spring proxy here, so the test opens its transaction.
    private ChargeView create(BillingService billing, NewOrganization organization) {
        return TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> transaction.execute(status -> created(billing.createCharge(ChargeRequest.of(brl(10_000))))));
    }

    private static String newTransactionId() {
        return TransactionId.generate(new SecureRandom()).value();
    }

    /** Yields the characters of the given transaction IDs, in order, as {@code TransactionId.generate} draws them. */
    private static final class ScriptedRandom extends SecureRandom {

        @Serial
        private static final long serialVersionUID = 1L;

        private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

        private final ArrayDeque<Character> characters;

        ScriptedRandom(List<String> transactionIds) {
            this.characters = transactionIds.stream()
                    .flatMap(id -> id.chars().mapToObj(c -> (char) c))
                    .collect(Collectors.toCollection(ArrayDeque::new));
        }

        @Override
        public int nextInt(int bound) {
            return ALPHABET.indexOf(characters.removeFirst());
        }
    }
}
