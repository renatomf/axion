package dev.axion.simulator.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

class EndToEndIdTest {

    private static final Instant FIRST_OF_2000 = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant LAST_OF_2099 = Instant.parse("2099-12-31T23:59:59.999Z");

    @Property
    void alwaysHasTheFormatOfTheSpiCatalog(
            @ForAll @StringLength(8) @NumericChars String ispb, @ForAll("instants") Instant createdAt) {
        var id = EndToEndId.generate(new Ispb(ispb), createdAt);

        assertThat(id.value()).hasSize(32).matches("E[0-9]{8}[0-9]{12}[A-Za-z0-9]{11}");
        assertThat(id.value().substring(1, 9)).isEqualTo(ispb);
    }

    @Provide
    Arbitrary<Instant> instants() {
        return Arbitraries.longs()
                .between(FIRST_OF_2000.toEpochMilli(), LAST_OF_2099.toEpochMilli())
                .map(Instant::ofEpochMilli);
    }

    @Test
    void carriesTheMinuteOfCreationInUtc() {
        var id = EndToEndId.generate(new Ispb("99990011"), Instant.parse("2026-11-21T14:30:59.999Z"));

        assertThat(id.value()).startsWith("E99990011202611211430");
    }

    @Test
    void drawsADifferentRandomPartEachTime() {
        var createdAt = Instant.parse("2026-11-21T14:30:00Z");
        var ispb = new Ispb("99990011");

        assertThat(EndToEndId.generate(ispb, createdAt)).isNotEqualTo(EndToEndId.generate(ispb, createdAt));
    }

    @Test
    void refusesAValueOutOfFormat() {
        assertThatThrownBy(() -> new EndToEndId("E99990011202611211430aB3dE5gH7j"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnIspbThatIsNotEightDigits() {
        assertThatThrownBy(() -> new Ispb("9999001")).isInstanceOf(IllegalArgumentException.class);
    }
}
