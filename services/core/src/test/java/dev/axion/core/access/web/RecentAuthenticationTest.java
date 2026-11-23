package dev.axion.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RecentAuthenticationTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void acceptsAnAuthenticationWithinTheMaximumAge() {
        assertThat(RecentAuthentication.isRecent(NOW.minus(Duration.ofMinutes(1)), CLOCK))
                .isTrue();
    }

    @Test
    void acceptsAnAuthenticationExactlyAtTheMaximumAge() {
        assertThat(RecentAuthentication.isRecent(NOW.minus(RecentAuthentication.MAX_AGE), CLOCK))
                .isTrue();
    }

    @Test
    void refusesAnAuthenticationOlderThanTheMaximumAge() {
        Instant justTooOld = NOW.minus(RecentAuthentication.MAX_AGE).minusSeconds(1);

        assertThat(RecentAuthentication.isRecent(justTooOld, CLOCK)).isFalse();
    }

    @Test
    void refusesATokenThatDoesNotSayWhenTheMemberAuthenticated() {
        assertThat(RecentAuthentication.isRecent(null, CLOCK)).isFalse();
    }

    @Test
    void allowsFiveMinutes() {
        assertThat(RecentAuthentication.MAX_AGE).isEqualTo(Duration.ofMinutes(5));
    }
}
