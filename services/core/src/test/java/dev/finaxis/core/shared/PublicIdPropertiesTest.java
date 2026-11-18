package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Properties that hold for every {@code PublicId}, over UUIDs with any of their 128 bits set. */
class PublicIdPropertiesTest {

    @Property(tries = 1000)
    void parsingTheTextGivesBackTheSameId(@ForAll("uuids") UUID uuid) {
        PublicId id = new PublicId("ch", uuid);

        assertThat(PublicId.parse("ch", id.toString())).isEqualTo(id);
    }

    /**
     * Public IDs sort as text in the same order as their UUIDs sort in PostgreSQL, which compares the
     * 16 bytes as unsigned numbers. A list ordered by ID is then the same list whichever form is used.
     */
    @Property(tries = 1000)
    void textOrderIsUuidOrder(@ForAll("uuids") UUID a, @ForAll("uuids") UUID b) {
        String textA = new PublicId("ch", a).toString();
        String textB = new PublicId("ch", b).toString();

        assertThat(Integer.signum(textA.compareTo(textB))).isEqualTo(Integer.signum(compareUnsigned(a, b)));
    }

    @Provide
    Arbitrary<UUID> uuids() {
        Arbitrary<Long> bits = Arbitraries.longs();
        return Combinators.combine(bits, bits).as(UUID::new);
    }

    private static int compareUnsigned(UUID a, UUID b) {
        int mostSignificant = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return mostSignificant != 0
                ? mostSignificant
                : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
