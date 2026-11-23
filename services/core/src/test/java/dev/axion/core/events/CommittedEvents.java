package dev.axion.core.events;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.TenantId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A listener of the tests for every domain event, so the publication registry records each one: the
 * events it received once their transaction committed, and the registry's publications, by tenant.
 * Tests that share it see each other's events, so they read only their own tenant's.
 */
@TestComponent
public class CommittedEvents {

    private final List<DomainEvent> received = new CopyOnWriteArrayList<>();
    private final JdbcClient jdbc;

    CommittedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @TransactionalEventListener
    void receive(DomainEvent event) {
        received.add(event);
    }

    /** The tenant's events of the type, in the order their transactions committed. */
    public <T extends DomainEvent> List<T> of(TenantId tenant, Class<T> type) {
        return received.stream()
                .filter(event -> event.tenant().equals(tenant))
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    /** The types of the tenant's events, in the order they were published and committed. */
    public List<String> types(TenantId tenant) {
        return received.stream()
                .filter(event -> event.tenant().equals(tenant))
                .map(event -> event.getClass().getName())
                .toList();
    }

    /**
     * The event types of the tenant's publications in the registry, oldest first; those of one
     * transaction share their time, so in no particular order among themselves. Only those of this
     * listener, which receives every event, so other consumers do not repeat them.
     */
    public List<String> publications(TenantId tenant) {
        return jdbc.sql("""
                        SELECT event_type FROM events.event_publication
                         WHERE serialized_event::jsonb #>> '{header,tenant,value}' = ?
                           AND listener_id LIKE ?
                         ORDER BY publication_date
                        """)
                .param(tenant.value().toString())
                .param(CommittedEvents.class.getName() + ".%")
                .query((row, number) -> row.getString("event_type"))
                .list();
    }
}
