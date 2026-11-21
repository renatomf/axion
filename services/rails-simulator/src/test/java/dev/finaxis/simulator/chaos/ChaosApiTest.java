package dev.finaxis.simulator.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.simulator.PostgresTestConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The chaos rules of {@code openapi/simulator.yaml}, over HTTP, with the operator token. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ChaosApiTest {

    private static final String AUTHORIZATION = "Bearer operator-token-for-tests-only-0000";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final MockMvcTester mvc;
    private final ChaosRules rules;

    @Autowired
    ChaosApiTest(MockMvcTester mvc, ChaosRules rules) {
        this.mvc = mvc;
        this.rules = rules;
    }

    @BeforeEach
    void noRules() {
        rules.clear();
    }

    @Test
    void addsARule() {
        MvcTestResult result =
                add("{\"fault\": \"fail\", \"match\": {\"transactionId\": \"chaosApi1\"}, \"times\": 2}");

        assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.id").asString().matches(UUID);
        assertThat(result).bodyJson().extractingPath("$.fault").isEqualTo("fail");
        assertThat(result).bodyJson().extractingPath("$.match.transactionId").isEqualTo("chaosApi1");
        assertThat(result).bodyJson().doesNotHavePath("$.match.endToEndId");
        assertThat(result).bodyJson().extractingPath("$.times").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.remaining").isEqualTo(2);
        assertThat(result).bodyJson().doesNotHavePath("$.min");
    }

    @Test
    void addsALatencyRuleWithItsRange() {
        MvcTestResult result = add("{\"fault\": \"latency\", \"min\": \"PT0.5S\", \"max\": \"PT20S\", \"times\": 1}");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.min").isEqualTo("PT0.5S");
        assertThat(result).bodyJson().extractingPath("$.max").isEqualTo("PT20S");
        assertThat(result).bodyJson().extractingPath("$.match").isEqualTo(Map.of());
    }

    @Test
    void addsADelayRuleWithItsDuration() {
        MvcTestResult result = add("{\"fault\": \"delay\", \"duration\": \"PT90S\", \"times\": 1}");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.duration").isEqualTo("PT90S");
        assertThat(result).bodyJson().doesNotHavePath("$.min");
    }

    @Test
    void listsTheRulesInForceOldestFirst() {
        add("{\"fault\": \"fail\", \"times\": 1}");
        add("{\"fault\": \"lost_response\", \"times\": 1}");

        MvcTestResult list = mvc.get()
                .uri("/chaos/rules")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange();

        assertThat(list).hasStatus(HttpStatus.OK);
        assertThat(list).bodyJson().extractingPath("$.rules[*].fault").isEqualTo(List.of("fail", "lost_response"));
    }

    @Test
    void removesARule() {
        add("{\"fault\": \"fail\", \"times\": 1}");
        String id = rules.list().getFirst().id().toString();

        assertThat(delete("/chaos/rules/" + id)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rules.list()).isEmpty();
        assertThat(delete("/chaos/rules/" + id))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(delete("/chaos/rules/not-a-uuid")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void removesEveryRule() {
        add("{\"fault\": \"fail\", \"times\": 1}");
        add("{\"fault\": \"fail\", \"times\": 1}");

        assertThat(delete("/chaos/rules")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rules.list()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"times": 1}                                                       | fault is required.
            {"fault": "drop", "times": 1}                                      | fault must be one of latency, fail, lost_response, delay.
            {"fault": "fail"}                                                  | times must be an integer from 1 to 1000.
            {"fault": "fail", "times": 0}                                      | times must be an integer from 1 to 1000.
            {"fault": "fail", "times": 1001}                                   | times must be an integer from 1 to 1000.
            {"fault": "latency", "max": "PT1S", "times": 1}                    | min and max are required for latency.
            {"fault": "latency", "min": "1s", "max": "PT1S", "times": 1}       | min must be an ISO 8601 duration of seconds, such as PT1.5S.
            {"fault": "latency", "min": "PT0S", "max": "PT21S", "times": 1}    | max must not exceed PT20S.
            {"fault": "latency", "min": "PT2S", "max": "PT1S", "times": 1}     | min must not exceed max.
            {"fault": "fail", "min": "PT1S", "times": 1}                       | min and max apply only to latency.
            {"fault": "delay", "times": 1}                                     | duration is required for delay.
            {"fault": "delay", "duration": "PT0S", "times": 1}                 | duration must be more than zero and at most PT3600S.
            {"fault": "delay", "duration": "PT3600.001S", "times": 1}          | duration must be more than zero and at most PT3600S.
            {"fault": "fail", "duration": "PT1S", "times": 1}                  | duration applies only to delay.
            {"fault": "fail", "match": {"endToEndId": "E1"}, "times": 1}       | match.endToEndId must be an EndToEndId.
            {"fault": "fail", "match": {"transactionId": "a-b"}, "times": 1}   | match.transactionId must have 1 to 25 letters or digits.
            """)
    void refusesAnInvalidRuleNamingTheField(String body, String detail) {
        assertThat(add(body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo(detail);
        assertThat(rules.list()).isEmpty();
    }

    @Test
    void requiresTheOperatorToken() {
        assertThat(mvc.get().uri("/chaos/rules")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post()
                        .uri("/chaos/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fault\": \"fail\", \"times\": 1}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.delete().uri("/chaos/rules")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(rules.list()).isEmpty();
    }

    private MvcTestResult add(String body) {
        return mvc.post()
                .uri("/chaos/rules")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult delete(String uri) {
        return mvc.delete()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange();
    }
}
