package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The database state Flyway's migrations leave, read after the context starts. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class DatabaseMigrationTest {

    private final JdbcClient jdbc;

    @Autowired
    DatabaseMigrationTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void appliesEveryMigrationSuccessfully() {
        var failed = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE NOT success")
                .query(Long.class)
                .single();
        var latest = jdbc.sql("SELECT max(installed_rank) FROM flyway_schema_history")
                .query(Integer.class)
                .single();

        assertThat(failed).isZero();
        assertThat(latest).isPositive();
    }

    @Test
    void closesThePublicSchemaToOtherRoles() {
        // PUBLIC is the pseudo-role every database role belongs to.
        var publicCanUse = jdbc.sql("SELECT has_schema_privilege('public', 'public', 'USAGE')")
                .query(Boolean.class)
                .single();
        var publicCanCreate = jdbc.sql("SELECT has_schema_privilege('public', 'public', 'CREATE')")
                .query(Boolean.class)
                .single();

        assertThat(publicCanUse).isFalse();
        assertThat(publicCanCreate).isFalse();
    }

    @Test
    void exemptsNoRoleFromRowLevelSecurity() {
        var exempt = jdbc.sql("SELECT rolname FROM pg_roles WHERE rolname LIKE 'finaxis%' AND rolbypassrls")
                .query(String.class)
                .list();

        assertThat(exempt).isEmpty();
    }
}
