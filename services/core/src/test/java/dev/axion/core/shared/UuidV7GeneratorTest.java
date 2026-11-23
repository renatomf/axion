package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00.123Z");

    @Test
    void isAVersion7RfcUuid() {
        UUID uuid = new UuidV7Generator(fixedAt(NOW)).next();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void startsWithTheClocksTimeInMilliseconds() {
        UUID uuid = new UuidV7Generator(fixedAt(NOW)).next();

        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void randomBitsNeverOverwriteTheVersionOrTheVariant() {
        RandomGenerator allOnes = () -> -1L;

        UUID uuid = new UuidV7Generator(fixedAt(NOW), allOnes).next();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void uuidsInTheSameMillisecondAreUnique() {
        UuidV7Generator generator = new UuidV7Generator(fixedAt(NOW));
        Set<UUID> uuids = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            uuids.add(generator.next());
        }

        assertThat(uuids).hasSize(10_000);
    }

    @Test
    void aLaterMillisecondSortsAfter() {
        UUID earlier = new UuidV7Generator(fixedAt(NOW)).next();
        UUID later = new UuidV7Generator(fixedAt(NOW.plusMillis(1))).next();

        assertThat(later).isGreaterThan(earlier);
    }

    @Test
    void rejectsAClockBefore1970() {
        UuidV7Generator generator = new UuidV7Generator(fixedAt(Instant.parse("1969-12-31T23:59:59Z")));

        assertThatThrownBy(generator::next).isInstanceOf(IllegalStateException.class);
    }

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
