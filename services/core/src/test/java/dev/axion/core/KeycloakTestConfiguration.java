package dev.axion.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A real Keycloak for tests, importing {@code infra/keycloak/realms/axion.json}, the file Compose
 * uses, so tests validate tokens of the realm as configured, not of a copy. {@code start-dev} suits
 * a container on a random port; Compose runs the production mode.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakTestConfiguration {

    /** The image of infra/compose/compose.yaml, pinned by the same digest; keep the two in sync. */
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "quay.io/keycloak/keycloak:26.8.2@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3");

    private static final String REALM_FILE = "../../infra/keycloak/realms/axion.json";

    static final int HTTP_PORT = 8080;
    private static final int MANAGEMENT_PORT = 9000;

    @Bean
    GenericContainer<?> keycloak() {
        return new GenericContainer<>(IMAGE)
                .withCommand("start-dev", "--import-realm")
                .withCopyFileToContainer(MountableFile.forHostPath(REALM_FILE), "/opt/keycloak/data/import/axion.json")
                .withEnv("KC_HEALTH_ENABLED", "true")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", KeycloakRealm.ADMIN_USERNAME)
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", KeycloakRealm.ADMIN_PASSWORD)
                // Fills the ${AXION_CONSOLE_CLIENT_SECRET} placeholder of the realm file.
                .withEnv("AXION_CONSOLE_CLIENT_SECRET", "test-only")
                .withExposedPorts(HTTP_PORT, MANAGEMENT_PORT)
                // Ready once the realm is imported and the server accepts requests.
                .waitingFor(Wait.forHttp("/health/ready").forPort(MANAGEMENT_PORT));
    }

    @Bean
    KeycloakRealm keycloakRealm(GenericContainer<?> keycloak) {
        return new KeycloakRealm(keycloak);
    }

    /** The container's port is known only once it runs. */
    @Bean
    DynamicPropertyRegistrar keycloakProperties(KeycloakRealm realm) {
        return registry -> {
            registry.add("axion.members.issuer", realm::issuer);
            registry.add("axion.members.clients", () -> "axion-console," + KeycloakRealm.TEST_CLIENT);
        };
    }
}
