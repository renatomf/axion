package dev.finaxis.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountTypeTest {

    @ParameterizedTest
    @CsvSource({"ASSET, DEBIT", "EXPENSE, DEBIT", "LIABILITY, CREDIT", "REVENUE, CREDIT"})
    void normalBalanceFollowsTheAccountType(AccountType type, Direction normalBalance) {
        assertThat(type.normalBalance()).isEqualTo(normalBalance);
    }

    @Test
    void eachDirectionHasAnOpposite() {
        assertThat(Direction.DEBIT.opposite()).isEqualTo(Direction.CREDIT);
        assertThat(Direction.CREDIT.opposite()).isEqualTo(Direction.DEBIT);
    }
}
