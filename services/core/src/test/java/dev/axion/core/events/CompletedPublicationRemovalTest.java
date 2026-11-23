package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.Probes.Probe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The registry's retention (events and webhooks design, section 4.2). */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class CompletedPublicationRemovalTest {

    private final CompletedPublicationRemoval removal;
    private final JdbcClient owner;
    private final Clock clock;

    @Autowired
    CompletedPublicationRemovalTest(CompletedPublicationRemoval removal, DatabaseOwner owner, Clock clock) {
        this.removal = removal;
        this.owner = owner.jdbc();
        this.clock = clock;
    }

    @Test
    void removesPublicationsCompletedMoreThanSevenDaysAgo() {
        var now = clock.instant();
        UUID old = completed(now.minus(Duration.ofDays(9)), now.minus(Duration.ofDays(8)));
        UUID recent = completed(now.minus(Duration.ofDays(6)), now.minus(Duration.ofDays(6)));

        removal.removeCompleted();

        assertThat(exists(old)).isFalse();
        assertThat(exists(recent)).isTrue();
    }

    @Test
    void neverRemovesAnIncompletePublication() {
        UUID owed = insert(clock.instant().minus(Duration.ofDays(30)), "FAILED");

        removal.removeCompleted();

        assertThat(exists(owed)).isTrue();
    }

    @Test
    void removesInBatchesUntilNoneIsLeft() {
        var completed = clock.instant().minus(Duration.ofDays(8));
        for (int i = 0; i < CompletedPublicationRemoval.BATCH_SIZE + 1; i++) {
            completed(completed, completed);
        }

        assertThat(removal.removeCompleted()).isGreaterThan(CompletedPublicationRemoval.BATCH_SIZE);
        assertThat(owner.sql("SELECT count(*) FROM events.event_publication WHERE completion_date < ?")
                        .param(clock.instant()
                                .minus(CompletedPublicationRemoval.RETENTION)
                                .atOffset(ZoneOffset.UTC))
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private UUID completed(Instant published, Instant completedAt) {
        UUID id = insert(published, "COMPLETED");
        owner.sql("UPDATE events.event_publication SET completion_date = ? WHERE id = ?")
                .params(completedAt.atOffset(ZoneOffset.UTC), id)
                .update();
        return id;
    }

    // A probe for a listener no context has, so a test that resubmits incomplete publications can
    // read it, finds no listener and leaves it failed.
    private UUID insert(Instant published, String status) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO events.event_publication
                               (id, listener_id, event_type, serialized_event, publication_date, status,
                                completion_attempts)
                        VALUES (?, 'tests.retention', ?, ?, ?, ?, 1)
                        """)
                .params(
                        id,
                        Probe.class.getName(),
                        "{\"id\":\"" + UUID.randomUUID() + "\"}",
                        published.atOffset(ZoneOffset.UTC),
                        status)
                .update();
        return id;
    }

    private boolean exists(UUID id) {
        return owner.sql("SELECT count(*) FROM events.event_publication WHERE id = ?")
                        .param(id)
                        .query(Long.class)
                        .single()
                == 1;
    }
}
