package dev.axion.core.webhooks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalReceiversTest {

    private final LocalReceivers local = new LocalReceivers(Set.of(4000, 8443));

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:4000/hooks",
                "https://localhost:8443/hooks",
                "HTTP://LOCALHOST:4000/hooks?source=axion"
            })
    void acceptsLocalhostOnAListedPort(String url) {
        assertThat(local.accepts(URI.create(url))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost/hooks",
                "http://localhost:4001/hooks",
                "http://127.0.0.1:4000/hooks",
                "http://[::1]:4000/hooks",
                "http://localhost.example.com:4000/hooks",
                "http://api.localhost:4000/hooks",
                "ftp://localhost:4000/hooks",
                "http://user@localhost:4000/hooks",
                "http://localhost:4000/hooks#fragment"
            })
    void refusesAnythingElse(String url) {
        assertThat(local.accepts(URI.create(url))).isFalse();
    }

    @Test
    void acceptsNothingWithoutPorts() {
        assertThat(LocalReceivers.NONE.accepts(URI.create("http://localhost:4000/hooks")))
                .isFalse();
        assertThat(LocalReceivers.NONE.receivesOn("localhost")).isFalse();
    }

    @Test
    void receivesOnLocalhostOnly() {
        assertThat(local.receivesOn("LocalHost")).isTrue();
        assertThat(local.receivesOn("localhost.example.com")).isFalse();
        assertThat(local.receivesOn(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 80, 1023, 65_536})
    void refusesAPrivilegedOrImpossiblePort(int port) {
        assertThatThrownBy(() -> new LocalReceivers(Set.of(port))).isInstanceOf(IllegalArgumentException.class);
    }
}
