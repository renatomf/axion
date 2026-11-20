package dev.finaxis.core.ledger.domain;

import static dev.finaxis.core.ledger.LedgerFixtures.BRL;
import static dev.finaxis.core.ledger.LedgerFixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.shared.Money;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostingTest {

    private static final Account SETTLEMENT = account("platform:settlement", AccountType.ASSET);
    private static final Account AVAILABLE = account("tenant:1:available", AccountType.LIABILITY);
    private static final Money TEN = Money.ofMinor(1000, BRL);

    @Test
    void debitRaisesAnAssetAndLowersALiability() {
        assertThat(Posting.debit(SETTLEMENT, TEN).balanceEffect()).isEqualTo(TEN);
        assertThat(Posting.debit(AVAILABLE, TEN).balanceEffect()).isEqualTo(TEN.negate());
    }

    @Test
    void creditLowersAnAssetAndRaisesALiability() {
        assertThat(Posting.credit(SETTLEMENT, TEN).balanceEffect()).isEqualTo(TEN.negate());
        assertThat(Posting.credit(AVAILABLE, TEN).balanceEffect()).isEqualTo(TEN);
    }

    @Test
    void reversedPostingUndoesTheOriginal() {
        Posting debit = Posting.debit(SETTLEMENT, TEN);

        assertThat(debit.reversed()).isEqualTo(Posting.credit(SETTLEMENT, TEN));
        assertThat(debit.balanceEffect().plus(debit.reversed().balanceEffect()).isZero())
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsAmountsThatAreNotPositive(long minorUnits) {
        assertThatThrownBy(() -> Posting.debit(SETTLEMENT, Money.ofMinor(minorUnits, BRL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsAnAmountInAnotherCurrency() {
        Money dollars = Money.ofMinor(1000, Currency.getInstance("USD"));

        assertThatThrownBy(() -> Posting.credit(AVAILABLE, dollars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("tenant:1:available");
    }

    @Test
    void rejectsABlankAccountCode() {
        assertThatThrownBy(() -> account(" ", AccountType.ASSET)).isInstanceOf(IllegalArgumentException.class);
    }
}
