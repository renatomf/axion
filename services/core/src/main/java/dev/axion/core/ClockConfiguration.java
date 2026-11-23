package dev.axion.core;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// UTC, as instants are stored; business dates come from BusinessCalendar, never from the clock's
// zone. Tests pass a fixed clock instead.
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
