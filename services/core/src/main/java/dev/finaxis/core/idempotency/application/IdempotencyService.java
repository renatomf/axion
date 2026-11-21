package dev.finaxis.core.idempotency.application;

import dev.finaxis.core.ModuleRoles;
import dev.finaxis.core.idempotency.CapturedResponse;
import dev.finaxis.core.idempotency.IdempotencyApi;
import dev.finaxis.core.idempotency.IdempotentRequest;
import dev.finaxis.core.idempotency.IdempotentResult;
import dev.finaxis.core.idempotency.persistence.JdbcIdempotencyKeys;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class IdempotencyService implements IdempotencyApi {

    private static final String ROLE = "finaxis_idempotency";

    private final JdbcIdempotencyKeys keys;
    private final ModuleRoles roles;
    private final Clock clock;

    public IdempotencyService(JdbcIdempotencyKeys keys, ModuleRoles roles, Clock clock) {
        this.keys = keys;
        this.roles = roles;
        this.clock = clock;
    }

    // Idempotency design, section 4.1. The operation runs as its caller, outside this module's role,
    // so the modules it calls switch to their own. READ COMMITTED, the default, lets the lookup see a
    // key committed after the lock was released.
    @Override
    @Transactional
    public IdempotentResult execute(IdempotentRequest request, Supplier<CapturedResponse> operation) {
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
}
