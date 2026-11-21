package dev.finaxis.simulator.operator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.simulator.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, ApiExceptionHandlerTest.FailingController.class})
class ApiExceptionHandlerTest {

    private final MockMvcTester mvc;

    @Autowired
    ApiExceptionHandlerTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void answersAnUnknownPathWithProblemDetails() {
        assertThat(mvc.get().uri("/no/such/path"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo(404);
    }

    @Test
    void hidesAnUnexpectedExceptionBehindAGenericDetail() {
        assertThat(mvc.get().uri("/test/failure"))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("An unexpected error occurred.");
    }

    @RestController
    static class FailingController {

        @GetMapping("/test/failure")
        String fail() {
            throw new IllegalStateException("internal detail that must not leak");
        }
    }
}
