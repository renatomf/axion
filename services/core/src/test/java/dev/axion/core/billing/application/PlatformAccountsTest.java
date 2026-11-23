package dev.axion.core.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.Environment;
import dev.axion.core.tenancy.TenancyApi;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link PlatformAccounts}, opened when the service starts, against a real PostgreSQL. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PlatformAccountsTest {

    private final PlatformAccounts accounts;
    private final TenancyApi tenancy;
    private final JdbcClient owner;

    @Autowired
    PlatformAccountsTest(PlatformAccounts accounts, TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.accounts = accounts;
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @ParameterizedTest
    @EnumSource(Environment.class)
    void opensEachEnvironmentsSettlementAccountForItsPlatformTenant(Environment environment) {
        var account = owner.sql("""
                        SELECT tenant_id, code, type, currency, allow_negative, is_hot
                          FROM ledger.accounts
                         WHERE id = ?
                        """)
                .param(accounts.settlement(environment).value())
                .query()
                .singleRow();

        assertThat(account)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "tenant_id",
                        tenancy.platformTenant(environment).value(),
                        "code",
                        "platform:settlement",
                        "type",
                        "ASSET",
                        "currency",
                        "BRL",
                        "allow_negative",
                        true,
                        "is_hot",
                        true));
    }

    @Test
    void keepsTestAndLiveMoneyInSeparateAccounts() {
        assertThat(accounts.settlement(Environment.TEST)).isNotEqualTo(accounts.settlement(Environment.LIVE));
    }

    @Test
    void findsTheSameAccountsWhenOpenedAgain() {
        var test = accounts.settlement(Environment.TEST);
        var live = accounts.settlement(Environment.LIVE);

        accounts.open();

        assertThat(accounts.settlement(Environment.TEST)).isEqualTo(test);
        assertThat(accounts.settlement(Environment.LIVE)).isEqualTo(live);
    }
}
