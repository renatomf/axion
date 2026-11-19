package dev.finaxis.core.ledger.persistence;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.Direction;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.ledger.EntryRequest;
import dev.finaxis.core.ledger.EntrySource;
import dev.finaxis.core.ledger.PostingId;
import dev.finaxis.core.ledger.PostingRequest;
import dev.finaxis.core.ledger.PostingView;
import dev.finaxis.core.ledger.domain.Entry;
import dev.finaxis.core.ledger.domain.Posting;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.UuidV7Generator;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Inserts and reads ledger entries and their postings (ledger design, sections 3.3 and 5.1). Both
 * are immutable, so there is no update or delete method.
 *
 * <p>Runs in the caller's transaction, like {@link JdbcAccounts}.
 */
@Repository
public class JdbcEntries {

    private final JdbcClient jdbc;

    public JdbcEntries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the entry's row unless its source already produced an entry for the same purpose
     * (invariant I4).
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes the check and the insert one atomic statement: when
     * two transactions post the same source at once, the second waits for the first to finish. If
     * the first commits, the second inserts nothing; if it rolls back, the second inserts its row.
     *
     * @return whether the row was inserted; {@code false} if the source was already posted
     */
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

    /**
     * An entry that a source produced: its ID and the tenant it was posted for.
     *
     * @param id the entry's primary key
     * @param tenantId the tenant of the source fact
     */
    public record SourcedEntry(EntryId id, UUID tenantId) {}

    /** The entry the source produced for its purpose, if there is one. */
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

    /**
     * Inserts the entry's postings, each with a new ID from {@code ids}. At most 32 single-row
     * statements, one per posting; the deferred trigger checks that they balance at commit.
     */
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

    /**
     * The entry's postings, in no particular order: their UUIDv7 IDs order them by millisecond only,
     * and an entry's postings are usually inserted within one.
     */
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

    /**
     * Up to {@code count} of the account's postings, in descending ID order, starting after {@code
     * after} when it is present.
     *
     * <p>Keyset pagination: {@code id < :after} with the index on {@code (account_id, id)} lets
     * PostgreSQL jump straight to the page and read only its rows, read backwards. {@code OFFSET}
     * would read and discard every row before the page, slower the deeper the page.
     */
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
