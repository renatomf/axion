package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the module structure of the core service (overview design, section 5.2).
 *
 * <p>{@link ApplicationModules#of(Class)} reads the compiled classes, not a running application:
 * each direct subpackage of {@code dev.finaxis.core} is a module. No Spring context or database is
 * started, so the test runs in about a second.
 */
class ModuleStructureTest {

    private final ApplicationModules modules = ApplicationModules.of(Application.class);

    /**
     * Fails on a dependency cycle between modules, or on a module using a type another module does
     * not expose (by default, anything outside the module's own package).
     */
    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    @Test
    void detectsEveryModule() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .contains("shared", "observability", "web");
    }
}
