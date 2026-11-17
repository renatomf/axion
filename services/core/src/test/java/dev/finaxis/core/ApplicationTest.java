package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Smoke test: the Spring application context starts.
 *
 * <p>{@code @SpringBootTest} finds the {@code @SpringBootApplication} class in this package and
 * starts the same context the service starts in production. The test fails if any bean cannot be
 * created or any configuration is invalid.
 */
@SpringBootTest
class ApplicationTest {

    private final ApplicationContext context;

    // JUnit asks Spring to resolve constructor parameters of test classes; @Autowired marks
    // this constructor for that, the same constructor injection used in production code.
    @Autowired
    ApplicationTest(ApplicationContext context) {
        this.context = context;
    }

    @Test
    void contextLoads() {
        assertThat(context.getBean(Application.class)).isNotNull();
    }
}
