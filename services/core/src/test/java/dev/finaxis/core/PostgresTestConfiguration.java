package dev.finaxis.core;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A real PostgreSQL for tests; test classes with the same configuration share one container. As in
 * every environment, the migrations run as the owner and the service logs in as {@code finaxis_app}.
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

    private static final Path LOGIN_ROLE_SCRIPT = Path.of("../../infra/postgres/create-login-role.sql");

    private static final String LOGIN_ROLE = "finaxis_app";
    private static final String LOGIN_ROLE_PASSWORD = "finaxis-app-test";

    @Bean
    @ServiceConnection(type = FlywayConnectionDetails.class)
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE)
                .withEnv("FINAXIS_APP_DB_PASSWORD", LOGIN_ROLE_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(LOGIN_ROLE_SCRIPT),
                        "/docker-entrypoint-initdb.d/create-login-role.sql");
    }

    @Bean
    JdbcConnectionDetails loginRole(PostgreSQLContainer postgres) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return LOGIN_ROLE;
            }

            @Override
            public @Nullable String getPassword() {
                return LOGIN_ROLE_PASSWORD;
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }

    @Bean
    DatabaseOwner databaseOwner(PostgreSQLContainer postgres) {
        return new DatabaseOwner(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
}
