package dev.axion.core.billing.application;

import dev.axion.core.billing.domain.Charge;
import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.AccountRequest;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.LedgerApi;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.TenancyApi;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The platform's settlement account in each environment, {@code platform:settlement}: Finaxis's
 * account at the Central Bank, which every Pix credit debits (billing design, section 8.3). Opened
 * at startup, each with its platform tenant bound, since Row-Level Security lets a module open only
 * the bound tenant's accounts.
 */
@Component
public class PlatformAccounts {

    static final String SETTLEMENT = "platform:settlement";

    private final LedgerApi ledger;
    private final TenancyApi tenancy;

    // Written once at startup, before the service takes requests; volatile publishes it to them.
    private volatile Map<Environment, AccountId> settlement = Map.of();

    PlatformAccounts(LedgerApi ledger, TenancyApi tenancy) {
        this.ledger = ledger;
        this.tenancy = tenancy;
    }

    /**
     * Opens the accounts, or finds them open: opening is idempotent, so every start and every
     * instance agree on the same accounts.
     */
    @EventListener(ApplicationReadyEvent.class)
    void open() {
        var opened = new EnumMap<Environment, AccountId>(Environment.class);
        for (Environment environment : Environment.values()) {
            opened.put(environment, openSettlement(environment));
        }
        settlement = Map.copyOf(opened);
    }

    /**
     * The environment's settlement account.
     *
     * @throws IllegalStateException before the accounts are open, a bug in the caller
     */
    public AccountId settlement(Environment environment) {
        AccountId account = settlement.get(environment);
        if (account == null) {
            throw new IllegalStateException("The platform's accounts are not open yet");
        }
        return account;
    }

    // A hot asset that may go negative: every settlement debits it, so its balance row is not
    // updated in each posting (ledger design, section 5.3).
    private AccountId openSettlement(Environment environment) {
        TenantId platform = tenancy.platformTenant(environment);
        var request = new AccountRequest(platform.value(), SETTLEMENT, AccountType.ASSET, Charge.CURRENCY, true, true);
        return TenantContext.call(platform, environment, () -> ledger.openAccount(request));
    }
}
