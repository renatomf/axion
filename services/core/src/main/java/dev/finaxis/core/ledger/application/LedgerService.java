package dev.finaxis.core.ledger.application;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountRequest;
import dev.finaxis.core.ledger.Balance;
import dev.finaxis.core.ledger.Cursor;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.ledger.EntryRequest;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.ledger.Page;
import dev.finaxis.core.ledger.PostResult;
import dev.finaxis.core.ledger.PostingRequest;
import dev.finaxis.core.ledger.PostingView;
import dev.finaxis.core.ledger.domain.Account;
import dev.finaxis.core.ledger.domain.Entry;
import dev.finaxis.core.ledger.domain.Posting;
import dev.finaxis.core.ledger.persistence.JdbcAccounts;
import dev.finaxis.core.ledger.persistence.JdbcBalances;
import dev.finaxis.core.ledger.persistence.JdbcEntries;
import dev.finaxis.core.shared.UuidV7Generator;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class LedgerService implements LedgerApi {

    private static final Comparator<AccountId> LOCK_ORDER = Comparator.comparing(AccountId::value);

    private final JdbcAccounts accounts;
    private final JdbcEntries entries;
    private final JdbcBalances balances;
    private final Clock clock;
    private final UuidV7Generator ids;

    public LedgerService(JdbcAccounts accounts, JdbcEntries entries, JdbcBalances balances, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.balances = balances;
        this.clock = clock;
        this.ids = new UuidV7Generator(clock);
    }

    @Override
    @Transactional
    public AccountId openAccount(AccountRequest request) {
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

    // Ledger design, section 5.1. A concurrent call with the same source waits at the claim. A short
    // balance rolls back to a savepoint, keeping the caller's work; an exception marks the whole
    // transaction for rollback, so the caller's change cannot commit without its entry.
    @Override
    @Transactional
    public PostResult post(EntryRequest request) {
        var entry = entryOf(new EntryId(ids.next()), request);
        var transaction = TransactionAspectSupport.currentTransactionStatus();
        var savepoint = transaction.createSavepoint();
        if (entries.claim(entry.id(), request)) {
            var shortAccount = updateBalances(entry);
            if (shortAccount.isPresent()) {
                // Undoes the claim too, so the source can be posted later.
                transaction.rollbackToSavepoint(savepoint);
                transaction.releaseSavepoint(savepoint);
                return new PostResult.InsufficientFunds(shortAccount.get());
            }
            entries.insertPostings(entry, ids);
            transaction.releaseSavepoint(savepoint);
            return new PostResult.Posted(entry.id());
        }
        transaction.releaseSavepoint(savepoint);
        // The source was posted before. Entries are never deleted, so it is there to read.
        var existing = entries.findBySource(request.source()).orElseThrow();
        // A source fact belongs to one tenant, so the same source for another tenant is a bug.
        if (!existing.tenantId().equals(request.tenantId())) {
            throw new IllegalStateException("Source " + request.source() + " was already posted for another tenant");
        }
        if (!sameLegs(entries.findPostings(existing.id()), request.postings())) {
            throw new IllegalStateException("Source " + request.source() + " was already posted with other postings");
        }
        return new PostResult.AlreadyPosted(existing.id());
    }

    @Override
    @Transactional(readOnly = true)
    public Balance balanceOf(AccountId account) {
        var balance = balances.find(account)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account " + account.value()));
        return new Balance(account, balance, clock.instant());
    }

    // One posting more than the limit tells whether there is a next page, so no cursor leads to an empty one.
    @Override
    @Transactional(readOnly = true)
    public Page<PostingView> history(AccountId account, Cursor cursor, int limit) {
        if (limit < 1 || limit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException(
                    "History limit must be between 1 and " + MAX_HISTORY_LIMIT + ": " + limit);
        }
        if (accounts.findAllById(Set.of(account)).isEmpty()) {
            throw new IllegalArgumentException("Unknown account " + account.value());
        }
        var postings = entries.history(account, cursor.after(), limit + 1);
        if (postings.size() <= limit) {
            return new Page<>(postings, Optional.empty());
        }
        var page = postings.subList(0, limit);
        return new Page<>(page, Optional.of(Cursor.after(page.getLast().id())));
    }

    // Ascending account ID, so two entries lock shared balance rows in the same order: one waits for
    // the other instead of a deadlock (ledger design, section 5.2).
    private Optional<AccountId> updateBalances(Entry entry) {
        Map<AccountId, Account> touched = entry.postings().stream()
                .map(Posting::account)
                .collect(Collectors.toMap(Account::id, Function.identity(), (same, again) -> same));
        var inLockOrder = entry.balanceChanges().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(LOCK_ORDER))
                .toList();
        for (var change : inLockOrder) {
            var account = Objects.requireNonNull(touched.get(change.getKey()));
            // A hot account's balance is folded in by a scheduled job instead (section 5.3).
            if (!account.hot() && !balances.apply(account, change.getValue())) {
                return Optional.of(account.id());
            }
        }
        return Optional.empty();
    }

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
                    return new Posting(account, leg.direction(), leg.amount());
                })
                .toList();
        return new Entry(id, postings, request.reverses());
    }

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
