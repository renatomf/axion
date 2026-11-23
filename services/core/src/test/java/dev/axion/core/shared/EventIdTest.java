package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventIdTest {

    @Test
    void showsItselfWithTheEventPrefix() {
        var uuid = UUID.fromString("0193a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");

        var publicId = new EventId(uuid).publicId();

        assertThat(publicId.toString()).startsWith("evt_");
        assertThat(PublicId.parse("evt", publicId.toString()).uuid()).isEqualTo(uuid);
    }
}
