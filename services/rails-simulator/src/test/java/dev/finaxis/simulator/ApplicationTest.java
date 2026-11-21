package dev.finaxis.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The application context starts, with every bean and configuration valid. */
@SpringBootTest
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
}
