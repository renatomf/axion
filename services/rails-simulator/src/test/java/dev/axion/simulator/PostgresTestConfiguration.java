package dev.axion.simulator;

import java.nio.file.Path;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A real PostgreSQL for tests; test classes with the same configuration share one container. The
 * simulator's database and role are created by the script Compose runs, and the simulator logs in as
 * that role, as in every environment.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    /**
     * The image of {@code infra/compose/compose.yaml}, pinned by the same digest; keep the two in sync.
     * Declared compatible because Testcontainers cannot recognize a digest-pinned name.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse(
                    "postgres:17.12-bookworm@sha256:40865bc877a289da75963d97d72b59633f639fc5af7a4e856bfa3714cae5b536")
            .asCompatibleSubstituteFor("postgres");

    private static final Path DATABASE_SCRIPT = Path.of("../../infra/rails-simulator/create-database.sql");

    private static final String DATABASE = "rails_simulator";
    private static final String ROLE = "rails_simulator";
    private static final String ROLE_PASSWORD = "rails-simulator-test";

    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE)
                .withEnv("RAILS_SIMULATOR_DB_PASSWORD", ROLE_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(DATABASE_SCRIPT), "/docker-entrypoint-initdb.d/create-database.sql");
    }

    @Bean
    JdbcConnectionDetails simulatorRole(PostgreSQLContainer postgres) {
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + DATABASE);
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return ROLE;
            }

            @Override
            public String getPassword() {
                return ROLE_PASSWORD;
            }

            @Override
            public String getJdbcUrl() {
                return url;
            }
        };
    }
}
