package dev.finaxis.core.idempotency.web;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static dev.finaxis.core.tenancy.Permission.CHARGES_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.access.TenantBoundAccess;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The idempotency step of {@code /v1} writes over HTTP (idempotency design, sections 3 and 4.3),
 * against an endpoint that exists only in this test.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, IdempotencyStepTest.WriteController.class})
@AutoConfigureMockMvc
class IdempotencyStepTest {

    static final String PATH = "/v1/idempotent_writes";

    @RestController
    static class WriteController {

        @RequiresPermission(CHARGES_WRITE)
        @PostMapping(PATH)
        String write() {
            return "written";
        }

        @RequiresPermission(CHARGES_WRITE)
        @GetMapping(PATH)
        String read() {
            return "read";
        }
    }

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private NewOrganization organization;
    private IssuedApiKey key;

    @Autowired
    IdempotencyStepTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
    }

    @BeforeEach
    void issueKey() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
        key = issued(CHARGES_WRITE);
    }

    @Test
    void requiresTheKeyOnAWrite() {
        assertProblem(post(key), HttpStatus.BAD_REQUEST, "idempotency_key_required");
    }

    @Test
    void refusesAMalformedKey() {
        for (String malformed : new String[] {"", "with space", "chave-é", "x".repeat(256)}) {
            assertProblem(post(key, malformed), HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
        }
    }

    @Test
    void refusesTwoKeys() {
        var twoKeys = mvc.post()
                .uri(PATH)
                .header("Authorization", "Bearer " + key.secret())
                .header(IdempotencyStep.HEADER, "first", "second");

        assertProblem(assertThat(twoKeys), HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
    }

    @Test
    void acceptsAKeyOfVisibleAscii() {
        post(key, "!" + "~".repeat(254)).hasStatusOk().hasBodyTextEqualTo("written");
    }

    @Test
    void requiresNoKeyOnARead() {
        assertThat(mvc.get().uri(PATH).header("Authorization", "Bearer " + key.secret()))
                .hasStatusOk()
                .hasBodyTextEqualTo("read");
    }

    @Test
    void checksTheScopeBeforeTheKey() {
        assertProblem(post(issued(CHARGES_READ)), HttpStatus.FORBIDDEN, "insufficient_permission");
    }

    private MvcTestResultAssert post(IssuedApiKey caller) {
        return assertThat(mvc.post().uri(PATH).header("Authorization", "Bearer " + caller.secret()));
    }

    private MvcTestResultAssert post(IssuedApiKey caller, String idempotencyKey) {
        return assertThat(mvc.post()
                .uri(PATH)
                .header("Authorization", "Bearer " + caller.secret())
                .header(IdempotencyStep.HEADER, idempotencyKey));
    }

    private static void assertProblem(MvcTestResultAssert result, HttpStatus status, String code) {
        result.hasStatus(status)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo(code);
    }

    private IssuedApiKey issued(Permission scope) {
        return AccessFixtures.issue(
                access, AccessFixtures.admin(organization, Environment.TEST), "Checkout", Set.of(scope));
    }
}
