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
import dev.finaxis.core.ledger.persistence.JdbcBalances;
import dev.finaxis.core.ledger.persistence.JdbcEntries;
import dev.finaxis.core.shared.UuidV7Generator;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * The ledger's implementation of {@link LedgerApi}. Other modules depend on the interface; Spring
 * passes them this bean, the only implementation.
 */
@Service
public class LedgerService implements LedgerApi {

    /** The order in which an entry locks balance rows: ascending account ID. */
    private static final Comparator<AccountId> LOCK_ORDER = Comparator.comparing(AccountId::value);

    private final JdbcAccounts accounts;
    private final JdbcEntries entries;
    private final JdbcBalances balances;
    private final UuidV7Generator ids;

    public LedgerService(JdbcAccounts accounts, JdbcEntries entries, JdbcBalances balances, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.balances = balances;
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
     * source key; update the balances; then insert its postings. The claim is what makes a repeat
     * safe: a concurrent call with the same source waits at the claim until this transaction ends.
     *
     * <p>{@code Propagation.NESTED} opens a savepoint when the caller already has a transaction,
     * and starts one when it has none. Marking it rollback-only undoes this call's writes, back to
     * the savepoint, and keeps the caller's own work; with no caller transaction, it rolls back the
     * transaction this call started.
     */
    @Override
    @Transactional(propagation = Propagation.NESTED)
    public PostResult post(EntryRequest request) {
        var entry = entryOf(new EntryId(ids.next()), request);
        if (entries.claim(entry.id(), request)) {
            var shortAccount = updateBalances(entry);
            if (shortAccount.isPresent()) {
                // Undoes the claim and any balance already changed, so the source can be posted later.
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new PostResult.InsufficientFunds(shortAccount.get());
            }
            entries.insertPostings(entry, ids);
            return new PostResult.Posted(entry.id());
        }
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

    /**
     * Changes the balance of each account the entry touches, once per account (ledger design,
     * section 5.2), and stops at the first guarded balance that would go below zero.
     *
     * <p>Each update locks the balance row until the transaction ends. Updating in ascending account
     * ID order means two entries touching the same accounts lock them in the same order: one waits
     * for the other, instead of each holding a row the other needs, which would be a deadlock.
     *
     * @return the first account, in ID order, whose balance was short, if one was
     */
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
