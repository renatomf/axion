package dev.axion.core.rails.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.rails.CreditResult;
import dev.axion.core.rails.PixCredit;
import dev.axion.core.rails.PixCreditHandler;
import dev.axion.core.rails.persistence.JdbcPixCredits;
import dev.axion.core.rails.persistence.JdbcPixCredits.Handled;
import dev.axion.core.rails.persistence.JdbcPixCredits.PendingCredit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hands the inbox's credits to the {@link PixCreditHandler}, at least once (billing design, section
 * 7.3). Each pass claims a bounded batch of due credits under a lease, hands each over outside the
 * inbox's transaction, then records what the handler did. A credit whose handling fails is retried
 * with backoff and never given up: money that arrived must be recorded.
 */
@Service
public class CreditProcessor {

    public static final String UNMATCHED = "axion.rails.credits.unmatched";

    static final int BATCH_SIZE = 16;

    /** Long enough for a batch to be handled; a processor that dies holding it frees it then. */
    static final Duration LEASE = Duration.ofSeconds(30);

    /** The longest wait between two attempts, as the rails' own retries. */
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    /** From this failed attempt on, each failure is an error for a person, not a warning. */
    static final int ATTEMPTS_BEFORE_ERROR = 5;

    private static final String ROLE = "axion_rails";

    private static final Logger LOG = LoggerFactory.getLogger(CreditProcessor.class);

    private final JdbcPixCredits credits;
    private final PixCreditHandler handler;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Counter unmatched;

    public CreditProcessor(
            JdbcPixCredits credits,
            PixCreditHandler handler,
            ModuleRoles roles,
            TransactionTemplate transactions,
            Clock clock,
            MeterRegistry meters) {
        this.credits = credits;
        this.handler = handler;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
        // Registered at zero, so an alert fires on the first one.
        this.unmatched = Counter.builder(UNMATCHED)
                .description("Credits whose transaction ID no charge has, left to an operator")
                .register(meters);
    }

    /** One pass: claims up to {@value #BATCH_SIZE} due credits and hands each over; returns how many. */
    public int processDue() {
        Instant now = now();
        List<PendingCredit> due = Objects.requireNonNull(transactions.execute(
                status -> roles.call(ROLE, () -> credits.claimDue(now, now.plus(LEASE), BATCH_SIZE))));
        for (PendingCredit pending : due) {
            process(pending);
        }
        return due.size();
    }

    /**
     * How long to wait after the {@code attempt}th failed attempt: a delay drawn uniformly up to
     * {@code min(5 minutes, 1 second × 2^(attempt − 1))}, the "full jitter" the rails use, so credits
     * that failed together are not retried together.
     */
    static Duration backoff(int attempt, RandomGenerator random) {
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempts are counted from 1, not " + attempt);
        }
        // 2^9 seconds is already past the cap, so the exponent never overflows.
        long ceiling = Math.min(MAX_BACKOFF.toMillis(), 1000L << Math.min(attempt - 1, 9));
        return Duration.ofMillis(random.nextLong(ceiling + 1));
    }

    // A failure to record the result leaves the credit received, due again once its lease ends; the
    // handler then answers ALREADY_RECORDED.
    private void process(PendingCredit pending) {
        PixCredit credit = pending.credit();
        int attempt = pending.attempts() + 1;
        CreditResult result;
        try {
            result = handler.handle(credit);
        } catch (RuntimeException failure) {
            retryLater(credit, attempt, failure);
            return;
        }
        Handled handled = result == CreditResult.UNKNOWN_TRANSACTION ? Handled.UNMATCHED : Handled.PROCESSED;
        transactions.executeWithoutResult(status -> roles.call(ROLE, () -> {
            credits.markHandled(credit.endToEndId(), handled, now());
            return null;
        }));
        if (handled == Handled.UNMATCHED) {
            unmatched.increment();
            LOG.error(
                    "Pix credit {} of message {} names a transaction ID no charge has: its money is in the"
                            + " settlement account and in no tenant's balance",
                    credit.endToEndId(),
                    credit.messageId());
        }
    }

    private void retryLater(PixCredit credit, int attempt, RuntimeException failure) {
        Instant next = now().plus(backoff(attempt, ThreadLocalRandom.current()));
        transactions.executeWithoutResult(status -> roles.call(ROLE, () -> {
            credits.markFailed(credit.endToEndId(), next);
            return null;
        }));
        if (attempt >= ATTEMPTS_BEFORE_ERROR) {
            LOG.error(
                    "Pix credit {} failed to be recorded {} times; retrying at {}",
                    credit.endToEndId(),
                    attempt,
                    next,
                    failure);
        } else {
            LOG.warn(
                    "Pix credit {} failed to be recorded, attempt {}; retrying at {}",
                    credit.endToEndId(),
                    attempt,
                    next,
                    failure);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
