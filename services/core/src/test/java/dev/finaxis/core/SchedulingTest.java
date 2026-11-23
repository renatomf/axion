package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** The scheduler that runs the core's jobs. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SchedulingTest {

    @Test
    void runsTheJobsSideBySide(@Autowired ThreadPoolTaskScheduler scheduler) {
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
    }
}
