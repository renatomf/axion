package dev.finaxis.core.access.security;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.MutableClock;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.access.TenantBoundAccess;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rate limits on {@code /v1} (tenancy design, section 6), with the test environment's buckets: 50
 * requests at once, then 25 a second, for the tenant and for each key. The clock stands still, so a
 * bucket refills only when a test moves it.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, RateLimitingTest.Configuration.class})
@AutoConfigureMockMvc
class RateLimitingTest {

    private static final int TEST_BURST = 50;

    private static final String RATE_LIMITED = """
            {"status": 429, "detail": "Too many requests; retry later.", "code": "rate_limited"}
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-20T12:00:00Z"));
        }

        @Bean
        LimitedController limitedController() {
            return new LimitedController();
        }
    }

    @RestController
    static class LimitedController {

        @RequiresPermission(CHARGES_READ)
        @GetMapping("/v1/limited")
        String limited() {
            return "reached";
        }
    }

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final MutableClock clock;

    @Autowired
    RateLimitingTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy, MutableClock clock) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.clock = clock;
    }

    @Test
    void refusesAKeyBeyondItsBurst() {
        IssuedApiKey key = issued(newOrganization());
        spendBurst(key);

        MvcTestResult refused = get(key);

        assertThat(refused)
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo(RATE_LIMITED);
        // 25 a second: the next token comes in 40 milliseconds, rounded up to a second.
        assertThat(refused).hasHeader(HttpHeaders.RETRY_AFTER, "1");
    }

    @Test
    void letsAKeyBackInOnceItsBucketRefills() {
        IssuedApiKey key = issued(newOrganization());
        spendBurst(key);
        assertThat(get(key)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        clock.advance(Duration.ofMillis(40));

        assertThat(get(key)).hasStatusOk();
    }

    @Test
    void sharesTheTenantsBudgetAmongItsKeys() {
        NewOrganization organization = newOrganization();
        IssuedApiKey first = issued(organization);
        IssuedApiKey second = issued(organization);
        spendBurst(first);

        assertThat(get(second)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void keepsAnotherTenantsKeyWorking() {
        spendBurst(issued(newOrganization()));

        assertThat(get(issued(newOrganization()))).hasStatusOk();
    }

    private void spendBurst(IssuedApiKey key) {
        for (int i = 0; i < TEST_BURST; i++) {
            assertThat(get(key)).hasStatusOk();
        }
    }

    private MvcTestResult get(IssuedApiKey key) {
        return mvc.get()
                .uri("/v1/limited")
                .header("Authorization", "Bearer " + key.secret())
                .exchange();
    }

    private NewOrganization newOrganization() {
        return tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    private IssuedApiKey issued(NewOrganization organization) {
        return AccessFixtures.issue(
                access, AccessFixtures.admin(organization, Environment.TEST), "Checkout", Set.of(CHARGES_READ));
    }
}
