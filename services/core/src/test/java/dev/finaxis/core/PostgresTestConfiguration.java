package dev.finaxis.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** A real PostgreSQL for tests; test classes with the same configuration share one container. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    /**
     * The image of {@code infra/compose/compose.yaml}, pinned by the same digest; keep the two in sync.
     * Declared compatible because Testcontainers cannot recognize a digest-pinned name.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse(
                    "postgres:17.12-bookworm@sha256:40865bc877a289da75963d97d72b59633f639fc5af7a4e856bfa3714cae5b536")
            .asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE);
    }

    @Bean
    DatabaseOwner databaseOwner(PostgreSQLContainer postgres) {
        return new DatabaseOwner(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
}
