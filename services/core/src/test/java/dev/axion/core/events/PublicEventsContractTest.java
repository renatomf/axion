package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.OpenApiContract;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.DomainEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Every public event, as the catalog renders it, against its schema in the {@code webhooks} section
 * of {@code v1.yaml}, or of {@code internal.yaml} for the types only the console receives (events and
 * webhooks design, section 5.2): strictly, as responses are checked.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PublicEventsContractTest {

    private final EventCatalog catalog;

    @Autowired
    PublicEventsContractTest(EventCatalog catalog) {
        this.catalog = catalog;
    }

    @ParameterizedTest
    @MethodSource("dev.axion.core.events.EventFixtures#catalog")
    void rendersEveryEventAsItsSchemaDescribes(DomainEvent event) {
        catalog.render(event)
                .ifPresent(
                        rendered -> contractOf(rendered.type()).assertEventConforms(rendered.type(), rendered.body()));
    }

    @Test
    void documentsEveryPublicTypeOnceAndNoOther() {
        List<String> documented = new ArrayList<>(OpenApiContract.v1().events());
        documented.addAll(OpenApiContract.internal().events());

        assertThat(documented).containsExactlyInAnyOrderElementsOf(catalog.publicTypes());
    }

    private static OpenApiContract contractOf(String type) {
        return OpenApiContract.internal().events().contains(type) ? OpenApiContract.internal() : OpenApiContract.v1();
    }
}
