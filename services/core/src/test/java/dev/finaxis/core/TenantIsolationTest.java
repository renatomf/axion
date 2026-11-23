package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.finaxis.core.OpenApiContract.Operation;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.TenantBoundAccess;
import dev.finaxis.core.billing.BillingApi;
import dev.finaxis.core.billing.BillingFixtures;
import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.ChargeRequest;
import dev.finaxis.core.billing.ChargeView;
import dev.finaxis.core.rails.CreditResult;
import dev.finaxis.core.rails.PixCredit;
import dev.finaxis.core.rails.PixCreditHandler;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The fourth layer of tenant isolation (platform overview, section 4.3): every operation of the
 * contracts, attempted by a member of one organization on another organization's resources. Each
 * attempt answers {@code 404 not_found} and changes nothing, and no list shows the other tenant. An
 * operation on {@code /v1} names no tenant: its attempt is made with the caller's API key, and what
 * it creates is the caller's.
 *
 * <p>The operations are read from the contracts, so a new one fails this suite until it has an
 * attempt here (tenancy design, section 8). The member has every permission and has just logged
 * in, so the tenant is the only reason left to refuse.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KeycloakTestConfiguration.class})
@AutoConfigureMockMvc
class TenantIsolationTest {

    private static final String ISSUE_BODY = """
            {"name": "Intruder", "scopes": ["charges:read"]}
            """;

    private static final String CHARGE_BODY = """
            {"amount": 100, "currency": "BRL"}
            """;

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final DatabaseOwner owner;
    private final BillingApi billing;
    private final PixCreditHandler settlement;

    private final Map<String, Attempt> attempts = Map.of(
            "getCurrentMember", this::currentMemberShowsNoOtherOrganization,
            "listApiKeys", this::listApiKeysHidesAnotherTenantsKeys,
            "issueApiKey", this::issueApiKeyIssuesNoKeyForAnotherTenant,
            "revokeApiKey", this::revokeApiKeyLeavesAnotherTenantsKeyWorking,
            "rollApiKey", this::rollApiKeyLeavesAnotherTenantsKeyUnrolled,
            "createCharge", this::createChargeCreatesNothingForAnotherTenant,
            "getCharge", this::getChargeHidesAnotherTenantsCharge,
            "listCharges", this::listChargesHidesAnotherTenantsCharges,
            "createRefund", this::createRefundRefundsNothingOfAnotherTenant);

    @Autowired
    TenantIsolationTest(
            MockMvcTester mvc,
            AccessApi access,
            TenancyApi tenancy,
            DatabaseOwner owner,
            BillingApi billing,
            PixCreditHandler settlement) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = owner;
        this.billing = billing;
        this.settlement = settlement;
    }

    @TestFactory
    Stream<DynamicTest> everyOperationHidesAnotherTenant() {
        return contracts()
                .flatMap(contract -> contract.operations().stream()
                        .map(operation ->
                                DynamicTest.dynamicTest(operation.toString(), () -> attempt(contract, operation))));
    }

    @Test
    void everyAttemptIsOfAnOperationInTheContracts() {
        assertThat(contracts()
                        .flatMap(contract -> contract.operations().stream())
                        .map(Operation::id))
                .containsAll(attempts.keySet());
    }

    private void attempt(OpenApiContract contract, Operation operation) {
        Attempt attempt = attempts.get(operation.id());
        if (attempt == null) {
            fail("%s has no cross-tenant attempt in TenantIsolationTest", operation);
            return;
        }
        attempt.run(new Request(contract, operation), scene());
    }

    private void currentMemberShowsNoOtherOrganization(Request request, Scene scene) {
        MvcTestResult result = request.send(scene, null);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result)
                .body()
                .asString()
                .contains(scene.caller().organization().publicId().toString())
                .doesNotContain(
                        scene.other().organization().publicId().toString(),
                        scene.other().test().publicId().toString(),
                        scene.other().live().publicId().toString());
    }

    private void listApiKeysHidesAnotherTenantsKeys(Request request, Scene scene) {
        assertNotFound(request.send(scene, null, scene.other().test().publicId()));

        MvcTestResult own = request.send(scene, null, scene.caller().test().publicId());
        assertThat(own).hasStatus(HttpStatus.OK);
        assertThat(own)
                .body()
                .asString()
                .contains(scene.callerKey().id().publicId().toString())
                .doesNotContain(scene.otherKey().id().publicId().toString());
    }

    private void issueApiKeyIssuesNoKeyForAnotherTenant(Request request, Scene scene) {
        assertNotFound(request.send(scene, ISSUE_BODY, scene.other().test().publicId()));

        assertUntouched(scene);
    }

    private void revokeApiKeyLeavesAnotherTenantsKeyWorking(Request request, Scene scene) {
        assertNotFound(request.send(
                scene,
                null,
                scene.other().test().publicId(),
                scene.otherKey().id().publicId()));
        // The caller's own tenant in the path, with the other tenant's key.
        assertNotFound(request.send(
                scene,
                null,
                scene.caller().test().publicId(),
                scene.otherKey().id().publicId()));

        assertUntouched(scene);
    }

    private void rollApiKeyLeavesAnotherTenantsKeyUnrolled(Request request, Scene scene) {
        assertNotFound(request.send(
                scene,
                null,
                scene.other().test().publicId(),
                scene.otherKey().id().publicId()));
        assertNotFound(request.send(
                scene,
                null,
                scene.caller().test().publicId(),
                scene.otherKey().id().publicId()));

        assertUntouched(scene);
    }

    private void createChargeCreatesNothingForAnotherTenant(Request request, Scene scene) {
        IssuedApiKey writer = AccessFixtures.issue(
                access,
                AccessFixtures.admin(scene.caller(), Environment.TEST),
                "Billing",
                Set.of(Permission.CHARGES_WRITE));

        // A body that names a tenant is refused: the field is not the request's.
        MvcTestResult naming = request.sendWithKey(
                writer, "charge-1", """
                {"amount": 100, "currency": "BRL", "tenant_id": "%s"}
                """.formatted(scene.other().test().publicId()));
        assertThat(naming)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("tenant_id"));

        assertThat(request.sendWithKey(writer, "charge-2", CHARGE_BODY)).hasStatus(HttpStatus.CREATED);
        assertThat(charges(scene.caller().test())).isOne();
        assertThat(charges(scene.other().test())).isZero();
    }

    private void getChargeHidesAnotherTenantsCharge(Request request, Scene scene) {
        ChargeId others = charge(scene.other().test());
        ChargeId own = charge(scene.caller().test());

        assertNotFound(request.sendWithKey(scene.callerKey(), others.publicId()));
        assertThat(request.sendWithKey(scene.callerKey(), own.publicId())).hasStatus(HttpStatus.OK);
    }

    private void listChargesHidesAnotherTenantsCharges(Request request, Scene scene) {
        ChargeId others = charge(scene.other().test());
        ChargeId own = charge(scene.caller().test());

        MvcTestResult result = request.sendWithKey(scene.callerKey());
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result)
                .body()
                .asString()
                .contains(own.publicId().toString())
                .doesNotContain(others.publicId().toString());
    }

    private void createRefundRefundsNothingOfAnotherTenant(Request request, Scene scene) {
        IssuedApiKey refunder = AccessFixtures.issue(
                access,
                AccessFixtures.admin(scene.caller(), Environment.TEST),
                "Back office",
                Set.of(Permission.REFUNDS_WRITE));
        ChargeId others = paidCharge(scene.other().test());
        ChargeId own = paidCharge(scene.caller().test());

        assertNotFound(request.sendWithKey(refunder, "refund-1", "{}", others.publicId()));
        assertThat(refunds(scene.other().test())).isZero();

        assertThat(request.sendWithKey(refunder, "refund-2", "{}", own.publicId()))
                .hasStatus(HttpStatus.CREATED);
        assertThat(refunds(scene.caller().test())).isOne();
    }

    private ChargeId charge(TenantId tenant) {
        return created(tenant).id();
    }

    private ChargeView created(TenantId tenant) {
        var request = ChargeRequest.of(Money.ofMinor(100, Currency.getInstance("BRL")));
        return TenantContext.call(
                tenant, Environment.TEST, () -> BillingFixtures.created(billing.createCharge(request)));
    }

    private ChargeId paidCharge(TenantId tenant) {
        ChargeView charge = created(tenant);
        var credit = new PixCredit(
                BillingFixtures.endToEndId(),
                "M99990011" + UUID.randomUUID().toString().replace("-", "").substring(0, 23),
                charge.transactionId(),
                charge.amount(),
                Instant.now(),
                "99990011");
        assertThat(settlement.handle(credit)).isEqualTo(CreditResult.RECORDED);
        return charge.id();
    }

    private long refunds(TenantId tenant) {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM billing.refunds WHERE tenant_id = ?")
                .param(tenant.value())
                .query(Long.class)
                .single());
    }

    private long charges(TenantId tenant) {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM billing.charges WHERE tenant_id = ?")
                .param(tenant.value())
                .query(Long.class)
                .single());
    }

    /** Two organizations, each with a key in its test tenant; the caller is the first one's admin. */
    private Scene scene() {
        String subject = UUID.randomUUID().toString();
        NewOrganization caller = tenancy.createOrganization("Caller", subject);
        NewOrganization other =
                tenancy.createOrganization("Other", UUID.randomUUID().toString());
        return new Scene(subject, caller, issued(caller), other, issued(other));
    }

    private IssuedApiKey issued(NewOrganization organization) {
        return AccessFixtures.issue(
                access,
                AccessFixtures.admin(organization, Environment.TEST),
                "Checkout",
                Set.of(Permission.CHARGES_READ));
    }

    /** The other tenant still has exactly its one key, neither revoked nor rolled. */
    private void assertUntouched(Scene scene) {
        TenantId tenant = scene.other().test();
        assertThat(access.keys(tenant)).singleElement().satisfies(key -> {
            assertThat(key.id()).isEqualTo(scene.otherKey().id());
            assertThat(key.revokedAt()).isEmpty();
            assertThat(key.expiresAt()).isEmpty();
        });
    }

    private static void assertNotFound(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
    }

    private static Stream<OpenApiContract> contracts() {
        return Stream.of(OpenApiContract.internal(), OpenApiContract.v1());
    }

    @FunctionalInterface
    private interface Attempt {
        void run(Request request, Scene scene);
    }

    private record Scene(
            String subject,
            NewOrganization caller,
            IssuedApiKey callerKey,
            NewOrganization other,
            IssuedApiKey otherKey) {}

    /** The operation as the contract documents it, sent with the caller's token and checked against the contract. */
    private final class Request {

        private final OpenApiContract contract;
        private final Operation operation;

        Request(OpenApiContract contract, Operation operation) {
            this.contract = contract;
            this.operation = operation;
        }

        MvcTestResult send(Scene scene, @Nullable String body, Object... pathVariables) {
            var request = mvc.method(HttpMethod.valueOf(operation.method()))
                    .uri(operation.path(), pathVariables)
                    .with(jwt().jwt(token -> token.subject(scene.subject())
                            .claim(
                                    "auth_time",
                                    Instant.now().minus(Duration.ofMinutes(1)).getEpochSecond())));
            if (body != null) {
                request = request.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            MvcTestResult result = request.exchange();
            contract.assertConforms(result);
            return result;
        }

        /** The operation of {@code /v1}, with no body, sent with an API key of the caller's tenant. */
        MvcTestResult sendWithKey(IssuedApiKey key, Object... pathVariables) {
            MvcTestResult result = mvc.method(HttpMethod.valueOf(operation.method()))
                    .uri(operation.path(), pathVariables)
                    .header("Authorization", "Bearer " + key.secret())
                    .exchange();
            contract.assertConforms(result);
            return result;
        }

        /** The operation of {@code /v1}, sent with an API key of the caller's tenant. */
        MvcTestResult sendWithKey(IssuedApiKey key, String idempotencyKey, String body, Object... pathVariables) {
            MvcTestResult result = mvc.method(HttpMethod.valueOf(operation.method()))
                    .uri(operation.path(), pathVariables)
                    .header("Authorization", "Bearer " + key.secret())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
                    .exchange();
            contract.assertConforms(result);
            return result;
        }
    }
}
