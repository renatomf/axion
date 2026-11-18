package dev.finaxis.core;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one {@link Clock} the service reads the current time from.
 *
 * <p>Classes that need the time take a {@code Clock} in their constructor and Spring passes this
 * bean. Tests pass {@code Clock.fixed(...)} instead, so time-dependent behavior is deterministic.
 * The clock is in UTC because instants are stored and compared in UTC; business dates in {@code
 * America/Sao_Paulo} are derived from an instant with {@code BusinessCalendar}, never from the
 * clock's zone.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
