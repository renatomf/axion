package dev.axion.core.idempotency.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.idempotency.CapturedResponse;
import dev.axion.core.idempotency.IdempotencyApi;
import dev.axion.core.idempotency.IdempotentRequest;
import dev.axion.core.idempotency.IdempotentResult;
import dev.axion.core.idempotency.persistence.JdbcIdempotencyKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class IdempotencyService implements IdempotencyApi {

    public static final String REQUESTS = "finaxis.idempotency.requests";

    private static final String ROLE = "finaxis_idempotency";

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyService.class);

    private final JdbcIdempotencyKeys keys;
    private final ModuleRoles roles;
    private final Clock clock;
    private final Counter executed;
    private final Counter replayed;
    private final Counter inProgress;
    private final Counter keyReused;

    // Registered at zero, so a rate is defined before the first request of each outcome.
    public IdempotencyService(JdbcIdempotencyKeys keys, ModuleRoles roles, Clock clock, MeterRegistry meters) {
        this.keys = keys;
        this.roles = roles;
        this.clock = clock;
        this.executed = requests(meters, "executed");
        this.replayed = requests(meters, "replayed");
        this.inProgress = requests(meters, "in_progress");
        this.keyReused = requests(meters, "key_reused");
    }

    // Idempotency design, section 4.1. The operation runs as its caller, outside this module's role,
    // so the modules it calls switch to their own. READ COMMITTED, the default, lets the lookup see a
    // key committed after the lock was released.
    @Override
    @Transactional
    public IdempotentResult execute(IdempotentRequest request, Supplier<CapturedResponse> operation) {
        var result = run(request, operation);
        report(request, result);
        return result;
    }

    private IdempotentResult run(IdempotentRequest request, Supplier<CapturedResponse> operation) {
        var transaction = TransactionAspectSupport.currentTransactionStatus();
        if (!transaction.isNewTransaction()) {
            throw new IllegalStateException("An idempotent request needs a transaction of its own");
        }
        var earlier = roles.call(ROLE, () -> claim(request));
        if (earlier.isPresent()) {
            return earlier.get();
        }
        var response = operation.get();
        if (transaction.isRollbackOnly() || response.status() >= 500) {
            // Marked here, so the commit rolls back quietly instead of failing on a mark left by a
            // module the operation called.
            transaction.setRollbackOnly();
            return new IdempotentResult.Executed(response);
        }
        return roles.call(ROLE, () -> {
            keys.insert(request, response, clock.instant());
            return new IdempotentResult.Executed(response);
        });
    }

    // Empty when the operation may run: the lock is held and the key is unused.
    private Optional<IdempotentResult> claim(IdempotentRequest request) {
        if (!keys.tryLock(request.tenant(), request.key())) {
            return Optional.of(new IdempotentResult.InProgress());
        }
        return keys.find(request.tenant(), request.key())
                .map(stored -> stored.fingerprint().equals(request.fingerprint())
                        ? new IdempotentResult.Replayed(stored.response())
                        : new IdempotentResult.KeyReused());
    }

    // Logs carry the key's hash, never the key: it is text the client chose (section 7).
    private void report(IdempotentRequest request, IdempotentResult result) {
        switch (result) {
            case IdempotentResult.Executed executedResult -> executed.increment();
            case IdempotentResult.Replayed replay -> {
                replayed.increment();
                LOG.info(
                        "Replayed idempotency key {}, first run by request {}",
                        request.key().hash(),
                        replay.response().correlationId());
            }
            case IdempotentResult.InProgress running -> inProgress.increment();
            case IdempotentResult.KeyReused reused -> {
                keyReused.increment();
                LOG.info(
                        "Refused idempotency key {}, used before for another request",
                        request.key().hash());
            }
        }
    }

    private static Counter requests(MeterRegistry meters, String outcome) {
        return Counter.builder(REQUESTS)
                .description("Public API writes with an idempotency key, by what the key decided")
                .tag("outcome", outcome)
                .register(meters);
    }
}
