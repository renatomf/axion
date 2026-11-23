package dev.axion.core.ledger.persistence;

import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.Direction;
import dev.axion.core.ledger.EntryId;
import dev.axion.core.ledger.EntryRequest;
import dev.axion.core.ledger.EntrySource;
import dev.axion.core.ledger.PostingId;
import dev.axion.core.ledger.PostingRequest;
import dev.axion.core.ledger.PostingView;
import dev.axion.core.ledger.domain.Entry;
import dev.axion.core.ledger.domain.Posting;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.UuidV7Generator;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Entries and postings are immutable: there is no update or delete.
@Repository
public class JdbcEntries {

    private final JdbcClient jdbc;

    public JdbcEntries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Invariant I4. ON CONFLICT DO NOTHING: a concurrent post of the same source waits, then inserts
    // nothing if the first commits.
    public boolean claim(EntryId id, EntryRequest request) {
        int inserted = jdbc.sql("""
                        INSERT INTO ledger.entries
                            (id, tenant_id, source_type, source_id, purpose,
                             effective_date, reverses_entry_id, correlation_id)
                        VALUES (:id, :tenantId, :sourceType, :sourceId, :purpose,
                                :effectiveDate, :reverses, :correlationId)
                        ON CONFLICT (source_type, source_id, purpose) DO NOTHING
                        """)
                .param("id", id.value())
                .param("tenantId", request.tenantId())
                .param("sourceType", request.source().type())
                .param("sourceId", request.source().id())
                .param("purpose", request.source().purpose())
                .param("effectiveDate", request.effectiveDate())
                .param("reverses", request.reverses().map(EntryId::value).orElse(null))
                .param("correlationId", request.correlationId())
                .update();
        return inserted == 1;
    }

    public record SourcedEntry(EntryId id, UUID tenantId) {}

    public Optional<SourcedEntry> findBySource(EntrySource source) {
        return jdbc.sql("""
                        SELECT id, tenant_id
                          FROM ledger.entries
                         WHERE source_type = :type AND source_id = :id AND purpose = :purpose
                        """)
                .param("type", source.type())
                .param("id", source.id())
                .param("purpose", source.purpose())
                .query((row, rowNumber) -> new SourcedEntry(
                        new EntryId(row.getObject("id", UUID.class)), row.getObject("tenant_id", UUID.class)))
                .optional();
    }

    // The deferred trigger checks that they balance at commit.
    public void insertPostings(Entry entry, UuidV7Generator ids) {
        for (Posting posting : entry.postings()) {
            jdbc.sql("""
                            INSERT INTO ledger.postings
                                (id, entry_id, account_id, tenant_id, currency, direction, amount)
                            VALUES (:id, :entryId, :accountId, :tenantId, :currency, :direction, :amount)
                            """)
                    .param("id", ids.next())
                    .param("entryId", entry.id().value())
                    .param("accountId", posting.account().id().value())
                    .param("tenantId", posting.account().tenantId())
                    .param("currency", posting.amount().currency().getCurrencyCode())
                    .param("direction", posting.direction().name())
                    .param("amount", posting.amount().minorUnits())
                    .update();
        }
    }

    // No order: postings of one entry share a millisecond, which is all a UUIDv7 orders by.
    public List<PostingRequest> findPostings(EntryId entry) {
        return jdbc.sql("""
                        SELECT account_id, direction, amount, currency
                          FROM ledger.postings
                         WHERE entry_id = :entryId
                        """)
                .param("entryId", entry.value())
                .query((row, rowNumber) -> new PostingRequest(
                        new AccountId(row.getObject("account_id", UUID.class)),
                        Direction.valueOf(row.getString("direction")),
                        Money.ofMinor(row.getLong("amount"), Currency.getInstance(row.getString("currency")))))
                .list();
    }

    // Keyset pagination on (account_id, id): a deep page costs the same as the first, unlike OFFSET.
    public List<PostingView> history(AccountId account, Optional<PostingId> after, int count) {
        var sql = """
                SELECT p.id, p.entry_id, e.source_type, e.source_id, e.purpose,
                       p.direction, p.amount, p.currency, e.effective_date, p.created_at
                  FROM ledger.postings p
                  JOIN ledger.entries e ON e.id = p.entry_id
                 WHERE p.account_id = :accountId
                """ + (after.isPresent() ? "   AND p.id < :after\n" : "") + """
                 ORDER BY p.id DESC
                 LIMIT :count
                """;
        var statement = jdbc.sql(sql).param("accountId", account.value()).param("count", count);
        if (after.isPresent()) {
            statement = statement.param("after", after.get().value());
        }
        return statement
                .query((row, rowNumber) -> new PostingView(
                        new PostingId(row.getObject("id", UUID.class)),
                        new EntryId(row.getObject("entry_id", UUID.class)),
                        new EntrySource(
                                row.getString("source_type"), row.getString("source_id"), row.getString("purpose")),
                        Direction.valueOf(row.getString("direction")),
                        Money.ofMinor(row.getLong("amount"), Currency.getInstance(row.getString("currency"))),
                        row.getObject("effective_date", LocalDate.class),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
