package dev.finaxis.core.ledger.application;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountRequest;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.ledger.EntryRequest;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.ledger.PostResult;
import dev.finaxis.core.ledger.PostingRequest;
import dev.finaxis.core.ledger.domain.Account;
import dev.finaxis.core.ledger.domain.Entry;
import dev.finaxis.core.ledger.domain.Posting;
import dev.finaxis.core.ledger.persistence.JdbcAccounts;
import dev.finaxis.core.ledger.persistence.JdbcEntries;
import dev.finaxis.core.shared.UuidV7Generator;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's implementation of {@link LedgerApi}. Other modules depend on the interface; Spring
 * passes them this bean, the only implementation.
 */
@Service
public class LedgerService implements LedgerApi {

    private final JdbcAccounts accounts;
    private final JdbcEntries entries;
    private final UuidV7Generator ids;

    public LedgerService(JdbcAccounts accounts, JdbcEntries entries, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.ids = new UuidV7Generator(clock);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code @Transactional} joins the caller's transaction, or starts one if there is none, so
     * the account and its balance row are inserted together or not at all.
     */
    @Override
    @Transactional
    public AccountId openAccount(AccountRequest request) {
        // The constructor checks the request's rules before anything is written.
        var account = new Account(
                new AccountId(ids.next()),
                request.tenantId(),
                request.code(),
                request.type(),
                request.currency(),
                request.allowNegative(),
                request.hot());
        if (accounts.insertIfAbsent(account)) {
            return account.id();
        }
        // The code was taken. Accounts are never deleted, so the existing one is there to read.
        var existing = accounts.findByCode(request.tenantId(), request.code()).orElseThrow();
        if (!termsOf(existing).equals(request)) {
            throw new IllegalStateException(
                    "Account " + request.code() + " is already open with other terms: " + termsOf(existing));
        }
        return existing.id();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Follows the ledger design, section 5.1: build the entry, which checks its rules; claim its
     * source key; then insert its postings. The claim is what makes a repeat safe: a concurrent
     * call with the same source waits at the claim until this transaction ends.
     */
    @Override
    @Transactional
    public PostResult post(EntryRequest request) {
        var entry = entryOf(new EntryId(ids.next()), request);
        if (entries.claim(entry.id(), request)) {
            entries.insertPostings(entry, ids);
            return new PostResult.Posted(entry.id());
        }
        // The source was posted before. Entries are never deleted, so it is there to read.
        var existing = entries.findBySource(request.source()).orElseThrow();
        if (!sameLegs(entries.findPostings(existing), request.postings())) {
            throw new IllegalStateException("Source " + request.source() + " was already posted with other postings");
        }
        return new PostResult.AlreadyPosted(existing);
    }

    /** The domain entry for the request, with the accounts its postings reference. */
    private Entry entryOf(EntryId id, EntryRequest request) {
        var referenced =
                request.postings().stream().map(PostingRequest::account).collect(Collectors.toSet());
        var found = accounts.findAllById(referenced);
        var postings = request.postings().stream()
                .map(leg -> {
                    var account = found.get(leg.account());
                    if (account == null) {
                        throw new IllegalArgumentException(
                                "Unknown account " + leg.account().value());
                    }
                    // The Posting and Entry constructors check amounts, currencies and balance.
                    return new Posting(account, leg.direction(), leg.amount());
                })
                .toList();
        return new Entry(id, postings, request.reverses());
    }

    /** Whether two lists hold the same legs, in any order. */
    private static boolean sameLegs(List<PostingRequest> stored, List<PostingRequest> requested) {
        return countsOf(stored).equals(countsOf(requested));
    }

    private static Map<PostingRequest, Long> countsOf(List<PostingRequest> legs) {
        return legs.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static AccountRequest termsOf(Account account) {
        return new AccountRequest(
                account.tenantId(),
                account.code(),
                account.type(),
                account.currency(),
                account.allowNegative(),
                account.hot());
    }
}
