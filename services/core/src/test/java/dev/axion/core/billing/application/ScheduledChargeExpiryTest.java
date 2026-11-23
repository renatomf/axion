package dev.axion.core.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The schedule of {@link ChargeExpiry}. The job runs for as long as the context is cached, so its
 * clock stands before any test's charges: no pass can expire a charge another test relies on.
 */
@SpringBootTest(properties = "finaxis.billing.expiry.enabled=true")
@Import({PostgresTestConfiguration.class, ScheduledChargeExpiryTest.Configuration.class})
class ScheduledChargeExpiryTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2000-01-01T00:00:00Z"));
        }
    }

    @Test
    void runsEvery30Seconds(@Autowired ScheduledTaskHolder scheduler) {
        assertThat(scheduler.getScheduledTasks())
                .map(ScheduledTask::getTask)
                .filteredOn(task -> task.toString().endsWith("ScheduledChargeExpiry.expireDue"))
                .singleElement()
                .isInstanceOfSatisfying(
                        IntervalTask.class,
                        task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(30)));
    }
}
