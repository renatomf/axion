package dev.finaxis.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.ledger.application.BalanceVerifier;
import dev.finaxis.core.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link LedgerApi#balanceOf} against a real PostgreSQL. Each call commits its own transaction, and
 * every test uses new accounts and new sources.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerQueriesTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private final LedgerApi ledger;
    private final BalanceVerifier verifier;

    private UUID tenant;
    private AccountId settlement;
    private AccountId available;

    @Autowired
    LedgerQueriesTest(LedgerApi ledger, BalanceVerifier verifier) {
        this.ledger = ledger;
        this.verifier = verifier;
    }

    @BeforeEach
    void openAccounts() {
        tenant = UUID.randomUUID();
        settlement = ledger.openAccount(
                new AccountRequest(tenant, "platform:settlement", AccountType.ASSET, BRL, true, false));
        available = ledger.openAccount(
                new AccountRequest(tenant, "tenant:available", AccountType.LIABILITY, BRL, false, false));
    }

    /** What a test commits must leave the ledger's invariants intact (ledger design, section 8). */
    @AfterEach
    void leavesTheLedgerSound() {
        assertThat(verifier.verify().passed()).isTrue();
    }

    @Test
    void readsANewAccountAtZero() {
        var balance = ledger.balanceOf(available);

        assertThat(balance.account()).isEqualTo(available);
        assertThat(balance.balance()).isEqualTo(Money.zero(BRL));
    }

    @Test
    void readsTheBalanceThePostingsLeft() {
        settle(brl(1_000));
        settle(brl(250));

        assertThat(ledger.balanceOf(available).balance()).isEqualTo(brl(1_250));
        assertThat(ledger.balanceOf(settlement).balance()).isEqualTo(brl(1_250));
    }

    @Test
    void rejectsAnUnknownAccount() {
        var unknown = new AccountId(UUID.randomUUID());

        assertThatThrownBy(() -> ledger.balanceOf(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown account");
    }

    /** Settles a payment: the platform receives the money and owes it to the tenant. */
    private void settle(Money amount) {
        var source = new EntrySource("test", UUID.randomUUID().toString(), "settlement");
        var result = ledger.post(new EntryRequest(
                tenant,
                source,
                LocalDate.of(2026, 11, 18),
                "corr-test",
                List.of(PostingRequest.debit(settlement, amount), PostingRequest.credit(available, amount))));
        assertThat(result).isInstanceOf(PostResult.Posted.class);
    }

    private static Money brl(long minorUnits) {
        return Money.ofMinor(minorUnits, BRL);
    }
}
