package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The publication registry's table (events and webhooks design, section 4.2): written by the login
 * role, as Modulith writes it, and closed to every module role. Nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PublicationRegistrySchemaTest {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    @Autowired
    PublicationRegistrySchemaTest(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Test
    void letsTheLoginRoleRecordAPublication() {
        UUID id = UUID.randomUUID();

        Long found = transaction.execute(status -> {
            status.setRollbackOnly();
            jdbc.sql("""
                            INSERT INTO events.event_publication
                                   (id, listener_id, event_type, serialized_event, publication_date)
                            VALUES (?, 'listener', 'type', '{}', ?)
                            """).params(id, Instant.now().atOffset(ZoneOffset.UTC)).update();
            return jdbc.sql("SELECT count(*) FROM events.event_publication WHERE id = ?")
                    .param(id)
                    .query(Long.class)
                    .single();
        });

        assertThat(found).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(DatabaseRole.class)
    void closesTheRegistryToEveryModuleRole(DatabaseRole role) {
        assertThatThrownBy(() -> transaction.execute(status -> {
                    role.actAs(jdbc);
                    return Objects.requireNonNull(jdbc.sql("SELECT count(*) FROM events.event_publication")
                            .query(Long.class)
                            .single());
                }))
                .rootCause()
                .hasMessageContaining("permission denied for schema events");
    }
}
