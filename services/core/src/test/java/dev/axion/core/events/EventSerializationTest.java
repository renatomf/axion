package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.DomainEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.core.EventSerializer;

/**
 * Every domain event of the catalog (events and webhooks design, section 3.3), written as the
 * publication registry stores it and read back equal, with the serializer the registry uses.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class EventSerializationTest {

    private final EventSerializer serializer;

    @Autowired
    EventSerializationTest(EventSerializer serializer) {
        this.serializer = serializer;
    }

    @ParameterizedTest
    @MethodSource("dev.axion.core.events.EventFixtures#catalog")
    void readsBackEveryEventAsItWasPublished(DomainEvent event) {
        Object serialized = serializer.serialize(event);

        assertThat(serializer.deserialize(serialized, event.getClass())).isEqualTo(event);
    }
}
