package dev.finaxis.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL for tests, started in Docker by Testcontainers.
 *
 * <p>Import it with {@code @Import(PostgresTestConfiguration.class)}. The container is a Spring
 * bean: it starts with the test application context and stops when the context closes. Spring caches
 * contexts between test classes with the same configuration, so those classes share one container.
 *
 * <p>{@code @ServiceConnection} makes Spring Boot connect to this container (its random host port,
 * user and password) instead of to the URL in the application configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    /**
     * The image of the local infrastructure (infra/compose/compose.yaml), pinned by the same digest;
     * keep the two in sync. Testcontainers cannot tell that a digest-pinned name is still the
     * official {@code postgres} image, so the code declares it.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse(
                    "postgres:17.12-bookworm@sha256:40865bc877a289da75963d97d72b59633f639fc5af7a4e856bfa3714cae5b536")
            .asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE);
    }
}
