package dev.finaxis.core.ledger.domain;

import static dev.finaxis.core.ledger.LedgerFixtures.BRL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

    @Test
    void acceptsAHotAccountThatAllowsANegativeBalance() {
        var settlement = account(true, true);

        assertThat(settlement.hot()).isTrue();
    }

    @Test
    void rejectsAHotAccountThatForbidsANegativeBalance() {
        assertThatThrownBy(() -> account(false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must allow a negative balance");
    }

    private static Account account(boolean allowNegative, boolean hot) {
        return new Account(
                new AccountId(UUID.randomUUID()),
                UUID.randomUUID(),
                "platform:settlement",
                AccountType.ASSET,
                BRL,
                allowNegative,
                hot);
    }
}
