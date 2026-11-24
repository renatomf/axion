package dev.axion.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.EventCatalog;
import dev.axion.core.notifications.domain.StreamPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The types the live stream carries (events and webhooks design, section 3.3): every public type of
 * the catalog, each with the permission that receives it.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class StreamTypesTest {

    @Test
    void areThePublicTypesOfTheCatalog(@Autowired EventCatalog catalog) {
        assertThat(StreamPermissions.types()).containsExactlyInAnyOrderElementsOf(catalog.publicTypes());
    }
}
