package dev.axion.core.events;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The events module's own reads of the publication registry, as {@code axion_events}, which sees how
 * far each publication has got and never the event it carries.
 */
@Repository
class JdbcPublications {

    private final JdbcClient jdbc;

    JdbcPublications(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long countIncomplete() {
        return jdbc.sql("SELECT count(*) FROM events.event_publication WHERE completion_date IS NULL")
                .query(Long.class)
                .single();
    }

    /** Failed publications that have had their last attempt. */
    long countDead() {
        return jdbc.sql("""
                        SELECT count(*) FROM events.event_publication
                         WHERE completion_date IS NULL AND status = 'FAILED' AND completion_attempts >= ?
                        """)
                .param(ResubmissionSchedule.ATTEMPTS)
                .query(Long.class)
                .single();
    }

    /** When the oldest incomplete publication that is not dead was published. */
    Optional<OffsetDateTime> oldestIncompleteAliveSince() {
        return jdbc.sql("""
                        SELECT publication_date FROM events.event_publication
                         WHERE completion_date IS NULL
                           AND (status IS DISTINCT FROM 'FAILED' OR completion_attempts < ?)
                         ORDER BY publication_date
                         LIMIT 1
                        """)
                .param(ResubmissionSchedule.ATTEMPTS)
                .query(OffsetDateTime.class)
                .optional();
    }
}
