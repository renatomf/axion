package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/** The application context starts, with every bean and configuration valid. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ApplicationTest {

    private final ApplicationContext context;

    @Autowired
    ApplicationTest(ApplicationContext context) {
        this.context = context;
    }

    @Test
    void contextLoads() {
        assertThat(context.getBean(Application.class)).isNotNull();
    }

    @Test
    void providesAClockInUtc() {
        assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
