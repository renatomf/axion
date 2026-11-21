package dev.finaxis.core;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static dev.finaxis.core.tenancy.Permission.CHARGES_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.ApiKeyCaller;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.access.TenantBoundAccess;
import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountRequest;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.EntryRequest;
import dev.finaxis.core.ledger.EntrySource;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.ledger.PostingRequest;
import dev.finaxis.core.shared.BusinessCalendar;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A test API key never reaches live data, money included (tenancy design, section 3.2). The key is
 * sent on {@code /v1} as a tenant's system sends it, to an endpoint that reads and posts on the
 * ledger with the request's binding: each environment of the organization is a tenant of its own,
 * with a platform tenant of its own, so the live side is out of reach.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, EnvironmentIsolationTest.LedgerController.class})
@AutoConfigureMockMvc
class EnvironmentIsolationTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Money TEN = Money.ofMinor(1_000, BRL);

    /** Reads and posts on the ledger as a {@code /v1} endpoint would, reporting a refusal as text. */
    @RestController
    static class LedgerController {

        private final LedgerApi ledger;
        private final AccessApi access;
        private final Clock clock;

        LedgerController(LedgerApi ledger, AccessApi access, Clock clock) {
            this.ledger = ledger;
            this.access = access;
            this.clock = clock;
        }

        @RequiresPermission(CHARGES_READ)
        @GetMapping("/v1/isolation/balances/{account}")
        String balance(@PathVariable UUID account) {
            return refusedAsText(() -> String.valueOf(
                    ledger.balanceOf(new AccountId(account)).balance().minorUnits()));
        }

        /** Moves ten reais from the debited account to the credited one, for the caller's tenant. */
        @RequiresPermission(CHARGES_WRITE)
        @PostMapping("/v1/isolation/settlements")
        String settle(ApiKeyCaller caller, @RequestParam UUID debit, @RequestParam UUID credit) {
            return refusedAsText(() -> ledger.post(new EntryRequest(
                            caller.tenant().value(),
                            new EntrySource("charge", "ch_" + UUID.randomUUID(), "settlement"),
                            BusinessCalendar.today(clock),
                            "isolation",
                            List.of(
                                    PostingRequest.debit(new AccountId(debit), TEN),
                                    PostingRequest.credit(new AccountId(credit), TEN))))
                    .getClass()
                    .getSimpleName());
        }

        @RequiresPermission(CHARGES_READ)
        @GetMapping("/v1/isolation/tenants/{tenant}/api_keys")
        String keys(@PathVariable UUID tenant) {
            return String.valueOf(access.keys(new TenantId(tenant)).size());
        }

        private static String refusedAsText(Supplier<String> work) {
            try {
                return work.get();
            } catch (IllegalArgumentException refused) {
                return "refused: " + refused.getMessage();
            }
        }
    }

    private final MockMvcTester mvc;
    private final LedgerApi ledger;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient owner;

    private NewOrganization organization;
    private IssuedApiKey testKey;
    private AccountId testAvailable;
    private AccountId liveAvailable;
    private AccountId testSettlement;
    private AccountId liveSettlement;

    @Autowired
    EnvironmentIsolationTest(
            MockMvcTester mvc, LedgerApi ledger, AccessApi access, TenancyApi tenancy, DatabaseOwner owner) {
        this.mvc = mvc;
        this.ledger = ledger;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = owner.jdbc();
    }

    /** One organization with an account in each tenant, and the settlement account of each platform tenant. */
    @BeforeEach
    void openAccounts() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
        testKey = AccessFixtures.issue(
                access,
                AccessFixtures.admin(organization, Environment.TEST),
                "Checkout",
                Set.of(CHARGES_READ, CHARGES_WRITE));
        AccessFixtures.issue(
                access, AccessFixtures.admin(organization, Environment.LIVE), "Checkout", Set.of(CHARGES_READ));

        testAvailable = openAvailable(organization.test(), Environment.TEST);
        liveAvailable = openAvailable(organization.live(), Environment.LIVE);
        testSettlement = openSettlement(Environment.TEST);
        liveSettlement = openSettlement(Environment.LIVE);
    }

    @Test
    void readsTheTestTenantsOwnBalance() {
        get("/v1/isolation/balances/" + testAvailable.value()).hasStatusOk().hasBodyTextEqualTo("0");
    }

    @Test
    void hidesTheLiveTenantsAccounts() {
        get("/v1/isolation/balances/" + liveAvailable.value())
                .hasStatusOk()
                .hasBodyTextEqualTo("refused: Unknown account " + liveAvailable.value());
    }

    @Test
    void settlesThroughTheTestPlatform() {
        settle(testSettlement, testAvailable).hasStatusOk().hasBodyTextEqualTo("Posted");

        get("/v1/isolation/balances/" + testAvailable.value()).hasStatusOk().hasBodyTextEqualTo("1000");
    }

    @Test
    void neverSettlesThroughTheLivePlatform() {
        settle(liveSettlement, testAvailable)
                .hasStatusOk()
                .hasBodyTextEqualTo("refused: Unknown account " + liveSettlement.value());

        assertThat(entriesOf(organization.test())).isZero();
    }

    @Test
    void neverPaysTheLiveTenant() {
        settle(testSettlement, liveAvailable)
                .hasStatusOk()
                .hasBodyTextEqualTo("refused: Unknown account " + liveAvailable.value());

        assertThat(entriesOf(organization.test())).isZero();
        assertThat(storedBalance(liveAvailable)).isZero();
    }

    @Test
    void hidesTheLiveTenantsKeys() {
        get("/v1/isolation/tenants/" + organization.test().value() + "/api_keys")
                .hasStatusOk()
                .hasBodyTextEqualTo("1");
        get("/v1/isolation/tenants/" + organization.live().value() + "/api_keys")
                .hasStatusOk()
                .hasBodyTextEqualTo("0");
    }

    private MvcTestResultAssert get(String path) {
        return assertThat(mvc.get().uri(path).header("Authorization", "Bearer " + testKey.secret()));
    }

    private MvcTestResultAssert settle(AccountId debit, AccountId credit) {
        return assertThat(mvc.post()
                .uri("/v1/isolation/settlements")
                .param("debit", debit.value().toString())
                .param("credit", credit.value().toString())
                .header("Authorization", "Bearer " + testKey.secret())
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private AccountId openAvailable(TenantId tenant, Environment environment) {
        return TenantContext.call(
                tenant,
                environment,
                () -> ledger.openAccount(new AccountRequest(
                        tenant.value(), "tenant:available", AccountType.LIABILITY, BRL, false, false)));
    }

    /** The platform tenant opens its own accounts, bound as the tenant; they are hot (ledger design, section 5.3). */
    private AccountId openSettlement(Environment environment) {
        TenantId platform = tenancy.platformTenant(environment);
        return TenantContext.call(
                platform,
                environment,
                () -> ledger.openAccount(new AccountRequest(
                        platform.value(), "platform:settlement", AccountType.ASSET, BRL, true, true)));
    }

    /** Read as the owner, so Row-Level Security cannot hide an entry that was posted. */
    private long entriesOf(TenantId tenant) {
        return owner.sql("SELECT count(*) FROM ledger.entries WHERE tenant_id = ?")
                .param(tenant.value())
                .query(Long.class)
                .single();
    }

    private long storedBalance(AccountId account) {
        return owner.sql("SELECT balance FROM ledger.balances WHERE account_id = ?")
                .param(account.value())
                .query(Long.class)
                .single();
    }
}
