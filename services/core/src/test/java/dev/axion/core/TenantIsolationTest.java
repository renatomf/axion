package dev.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.axion.core.OpenApiContract.Operation;
import dev.axion.core.access.AccessApi;
import dev.axion.core.access.AccessFixtures;
import dev.axion.core.access.IssuedApiKey;
import dev.axion.core.access.TenantBoundAccess;
import dev.axion.core.billing.BillingApi;
import dev.axion.core.billing.BillingFixtures;
import dev.axion.core.billing.ChargeId;
import dev.axion.core.billing.ChargeRequest;
import dev.axion.core.billing.ChargeView;
import dev.axion.core.rails.CreditResult;
import dev.axion.core.rails.PixCredit;
import dev.axion.core.rails.PixCreditHandler;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.TestDeliveries;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhookDeliveryId;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
@Import({PostgresTestConfiguration.class, KeycloakTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class TenantIsolationTest {

    private static final String ISSUE_BODY = """
            {"name": "Intruder", "scopes": ["charges:read"]}
            """;

    private static final String CHARGE_BODY = """
            {"amount": 100, "currency": "BRL"}
            """;

    private static final String ENDPOINT_URL = "https://orders.example.com/axion";

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final DatabaseOwner owner;
    private final BillingApi billing;
    private final PixCreditHandler settlement;
    private final WebhooksApi webhooks;

    private final Map<String, Attempt> attempts = Map.ofEntries(
            Map.entry("getCurrentMember", this::currentMemberShowsNoOtherOrganization),
            Map.entry("listApiKeys", this::listApiKeysHidesAnotherTenantsKeys),
            Map.entry("issueApiKey", this::issueApiKeyIssuesNoKeyForAnotherTenant),
            Map.entry("revokeApiKey", this::revokeApiKeyLeavesAnotherTenantsKeyWorking),
            Map.entry("rollApiKey", this::rollApiKeyLeavesAnotherTenantsKeyUnrolled),
            Map.entry("createCharge", this::createChargeCreatesNothingForAnotherTenant),
            Map.entry("getCharge", this::getChargeHidesAnotherTenantsCharge),
            Map.entry("listCharges", this::listChargesHidesAnotherTenantsCharges),
            Map.entry("createRefund", this::createRefundRefundsNothingOfAnotherTenant),
            Map.entry("createWebhookEndpoint", this::createWebhookEndpointRegistersNothingForAnotherTenant),
            Map.entry("listWebhookEndpoints", this::listWebhookEndpointsHidesAnotherTenantsEndpoints),
            Map.entry("getWebhookEndpoint", this::getWebhookEndpointHidesAnotherTenantsEndpoint),
            Map.entry("updateWebhookEndpoint", this::updateWebhookEndpointLeavesAnotherTenantsEndpointAsItWas),
            Map.entry("deleteWebhookEndpoint", this::deleteWebhookEndpointLeavesAnotherTenantsEndpointInPlace),
            Map.entry("rollWebhookEndpointSecret", this::rollWebhookEndpointSecretLeavesAnotherTenantsSecretInPlace),
            Map.entry("listWebhookDeliveries", this::listWebhookDeliveriesHidesAnotherTenantsLog),
            Map.entry("getWebhookDelivery", this::getWebhookDeliveryHidesAnotherTenantsDelivery),
            Map.entry("listTenantWebhookEndpoints", this::listTenantWebhookEndpointsHidesAnotherTenantsEndpoints),
            Map.entry("getTenantWebhookEndpoint", this::getTenantWebhookEndpointHidesAnotherTenantsEndpoint),
            Map.entry("createTenantWebhookEndpoint", this::createTenantWebhookEndpointRegistersNothingForAnotherTenant),
            Map.entry(
                    "updateTenantWebhookEndpoint",
                    this::updateTenantWebhookEndpointLeavesAnotherTenantsEndpointAsItWas),
            Map.entry(
                    "deleteTenantWebhookEndpoint",
                    this::deleteTenantWebhookEndpointLeavesAnotherTenantsEndpointInPlace),
            Map.entry(
                    "rollTenantWebhookEndpointSecret",
                    this::rollTenantWebhookEndpointSecretLeavesAnotherTenantsSecretInPlace));

    @Autowired
    TenantIsolationTest(
            MockMvcTester mvc,
            AccessApi access,
            TenancyApi tenancy,
            DatabaseOwner owner,
            BillingApi billing,
            PixCreditHandler settlement,
            WebhooksApi webhooks) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = owner;
        this.billing = billing;
        this.settlement = settlement;
        this.webhooks = webhooks;
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

    private void createWebhookEndpointRegistersNothingForAnotherTenant(Request request, Scene scene) {
        IssuedApiKey manager = webhookManager(scene);

        // A body that names a tenant is refused: the field is not the request's.
        MvcTestResult naming = request.sendWithKey(
                manager, "endpoint-1", """
                {"url": "https://orders.example.com/axion", "enabled_events": ["charge.paid"], "tenant_id": "%s"}
                """.formatted(scene.other().test().publicId()));
        assertThat(naming)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("tenant_id"));

        assertThat(request.sendWithKey(manager, "endpoint-2", """
                        {"url": "https://orders.example.com/axion", "enabled_events": ["charge.paid"]}
                        """)).hasStatus(HttpStatus.CREATED);
        assertThat(endpoints(scene.caller().test())).isOne();
        assertThat(endpoints(scene.other().test())).isZero();
    }

    private long endpoints(TenantId tenant) {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM webhooks.endpoints WHERE tenant_id = ?")
                .param(tenant.value())
                .query(Long.class)
                .single());
    }

    private void listWebhookEndpointsHidesAnotherTenantsEndpoints(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());

        MvcTestResult result = request.sendWithKey(webhookManager(scene));
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result)
                .body()
                .asString()
                .contains(own.publicId().toString())
                .doesNotContain(others.publicId().toString());
    }

    private void getWebhookEndpointHidesAnotherTenantsEndpoint(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());
        IssuedApiKey manager = webhookManager(scene);

        assertNotFound(request.sendWithKey(manager, others.publicId()));
        assertThat(request.sendWithKey(manager, own.publicId())).hasStatus(HttpStatus.OK);
    }

    private void listWebhookDeliveriesHidesAnotherTenantsLog(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        delivery(scene.other().test(), others);
        WebhookEndpointId own = endpoint(scene.caller().test());
        WebhookDeliveryId owned = delivery(scene.caller().test(), own);
        IssuedApiKey manager = webhookManager(scene);

        assertNotFound(request.sendWithKey(manager, others.publicId()));
        MvcTestResult result = request.sendWithKey(manager, own.publicId());
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).body().asString().contains(owned.publicId().toString());
    }

    private void getWebhookDeliveryHidesAnotherTenantsDelivery(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookDeliveryId othersDelivery = delivery(scene.other().test(), others);
        WebhookEndpointId own = endpoint(scene.caller().test());
        WebhookDeliveryId owned = delivery(scene.caller().test(), own);
        IssuedApiKey manager = webhookManager(scene);

        assertNotFound(request.sendWithKey(manager, others.publicId(), othersDelivery.publicId()));
        // The caller's own endpoint in the path, with the other tenant's delivery.
        assertNotFound(request.sendWithKey(manager, own.publicId(), othersDelivery.publicId()));
        assertThat(request.sendWithKey(manager, own.publicId(), owned.publicId()))
                .hasStatus(HttpStatus.OK);
    }

    private void updateWebhookEndpointLeavesAnotherTenantsEndpointAsItWas(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());
        IssuedApiKey manager = webhookManager(scene);
        String patch = """
                {"url": "https://attacker.example.com/hooks"}
                """;

        assertNotFound(request.sendBodyWithKey(manager, patch, others.publicId()));
        assertThat(TestCaller.call(scene.other().test(), Environment.TEST, () -> webhooks.endpoint(others)))
                .hasValueSatisfying(endpoint -> assertThat(endpoint.url()).isEqualTo(ENDPOINT_URL));

        assertThat(request.sendBodyWithKey(manager, patch, own.publicId())).hasStatus(HttpStatus.OK);
    }

    private void deleteWebhookEndpointLeavesAnotherTenantsEndpointInPlace(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());
        IssuedApiKey manager = webhookManager(scene);

        assertNotFound(request.sendWithKey(manager, others.publicId()));
        assertThat(TestCaller.call(scene.other().test(), Environment.TEST, () -> webhooks.endpoint(others)))
                .isPresent();

        assertThat(request.sendWithKey(manager, own.publicId())).hasStatus(HttpStatus.NO_CONTENT);
    }

    private void rollWebhookEndpointSecretLeavesAnotherTenantsSecretInPlace(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());
        IssuedApiKey manager = webhookManager(scene);

        assertNotFound(request.sendWithKey(manager, "roll-1", "{}", others.publicId()));
        assertThat(secrets(others)).isOne();

        assertThat(request.sendWithKey(manager, "roll-2", "{}", own.publicId())).hasStatus(HttpStatus.OK);
        assertThat(secrets(own)).isEqualTo(2);
    }

    private void listTenantWebhookEndpointsHidesAnotherTenantsEndpoints(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());

        assertNotFound(request.send(scene, null, scene.other().test().publicId()));

        MvcTestResult result = request.send(scene, null, scene.caller().test().publicId());
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result)
                .body()
                .asString()
                .contains(own.publicId().toString())
                .doesNotContain(others.publicId().toString());
    }

    private void createTenantWebhookEndpointRegistersNothingForAnotherTenant(Request request, Scene scene) {
        String body = """
                {"url": "https://orders.example.com/axion", "enabled_events": ["charge.paid"]}
                """;

        assertNotFound(request.send(scene, body, scene.other().test().publicId()));
        assertThat(endpoints(scene.other().test())).isZero();

        assertThat(request.send(scene, body, scene.caller().test().publicId())).hasStatus(HttpStatus.CREATED);
        assertThat(endpoints(scene.caller().test())).isOne();
    }

    private void updateTenantWebhookEndpointLeavesAnotherTenantsEndpointAsItWas(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());
        String patch = """
                {"url": "https://attacker.example.com/hooks"}
                """;

        assertNotFound(request.send(scene, patch, scene.other().test().publicId(), others.publicId()));
        assertNotFound(request.send(scene, patch, scene.caller().test().publicId(), others.publicId()));
        assertThat(TestCaller.call(scene.other().test(), Environment.TEST, () -> webhooks.endpoint(others)))
                .hasValueSatisfying(endpoint -> assertThat(endpoint.url()).isEqualTo(ENDPOINT_URL));

        assertThat(request.send(scene, patch, scene.caller().test().publicId(), own.publicId()))
                .hasStatus(HttpStatus.OK);
    }

    private void deleteTenantWebhookEndpointLeavesAnotherTenantsEndpointInPlace(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());

        assertNotFound(request.send(scene, null, scene.other().test().publicId(), others.publicId()));
        assertNotFound(request.send(scene, null, scene.caller().test().publicId(), others.publicId()));
        assertThat(TestCaller.call(scene.other().test(), Environment.TEST, () -> webhooks.endpoint(others)))
                .isPresent();

        assertThat(request.send(scene, null, scene.caller().test().publicId(), own.publicId()))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    private void rollTenantWebhookEndpointSecretLeavesAnotherTenantsSecretInPlace(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());

        assertNotFound(request.send(scene, null, scene.other().test().publicId(), others.publicId()));
        assertNotFound(request.send(scene, null, scene.caller().test().publicId(), others.publicId()));
        assertThat(secrets(others)).isOne();

        assertThat(request.send(scene, null, scene.caller().test().publicId(), own.publicId()))
                .hasStatus(HttpStatus.OK);
        assertThat(secrets(own)).isEqualTo(2);
    }

    private void getTenantWebhookEndpointHidesAnotherTenantsEndpoint(Request request, Scene scene) {
        WebhookEndpointId others = endpoint(scene.other().test());
        WebhookEndpointId own = endpoint(scene.caller().test());

        assertNotFound(request.send(scene, null, scene.other().test().publicId(), others.publicId()));
        // The caller's own tenant in the path, with the other tenant's endpoint.
        assertNotFound(request.send(scene, null, scene.caller().test().publicId(), others.publicId()));

        assertThat(request.send(scene, null, scene.caller().test().publicId(), own.publicId()))
                .hasStatus(HttpStatus.OK);
    }

    private long secrets(WebhookEndpointId endpoint) {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM webhooks.secrets WHERE endpoint_id = ?")
                .param(endpoint.value())
                .query(Long.class)
                .single());
    }

    private IssuedApiKey webhookManager(Scene scene) {
        return AccessFixtures.issue(
                access,
                AccessFixtures.admin(scene.caller(), Environment.TEST),
                "Integrations",
                Set.of(Permission.WEBHOOKS_MANAGE));
    }

    private WebhookEndpointId endpoint(TenantId tenant) {
        var request = new EndpointRequest(ENDPOINT_URL, Optional.empty(), Set.of("charge.paid"));
        var result = TestCaller.call(tenant, Environment.TEST, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint().id();
    }

    private WebhookDeliveryId delivery(TenantId tenant, WebhookEndpointId endpoint) {
        UUID id = new TestDeliveries(owner.jdbc())
                .logged(UUID.randomUUID(), tenant, endpoint, "charge.paid", "SUCCEEDED");
        return new WebhookDeliveryId(id);
    }

    private ChargeId charge(TenantId tenant) {
        return created(tenant).id();
    }

    private ChargeView created(TenantId tenant) {
        var request = ChargeRequest.of(Money.ofMinor(100, Currency.getInstance("BRL")));
        return TestCaller.call(tenant, Environment.TEST, () -> BillingFixtures.created(billing.createCharge(request)));
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

        /** The operation of {@code /v1}, with a body and no {@code Idempotency-Key}, sent with a key. */
        MvcTestResult sendBodyWithKey(IssuedApiKey key, String body, Object... pathVariables) {
            MvcTestResult result = mvc.method(HttpMethod.valueOf(operation.method()))
                    .uri(operation.path(), pathVariables)
                    .header("Authorization", "Bearer " + key.secret())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
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
