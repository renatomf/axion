package dev.axion.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** The module structure of the core (overview design, section 5.2), from the compiled classes. */
class ModuleStructureTest {

    private final ApplicationModules modules = ApplicationModules.of(Application.class);

    /** No cycles between modules, and no use of a type another module does not expose. */
    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    @Test
    void detectsEveryModule() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .contains(
                        "shared",
                        "observability",
                        "web",
                        "access",
                        "ledger",
                        "tenancy",
                        "idempotency",
                        "billing",
                        "risk",
                        "rails",
                        "events");
    }
}
