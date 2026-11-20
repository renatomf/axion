package dev.finaxis.core;

import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a module's work under the module's database role, so its statements have only that role's
 * privileges, even inside another module's transaction (tenancy design, section 5.6).
 */
@Component
public class ModuleRoles {

    private final JdbcClient jdbc;

    ModuleRoles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs the work as the role, then restores the caller's role. The switch is transaction-local,
     * so it needs a transaction and never outlives one on a pooled connection.
     */
    public <T> T call(String role, Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Switching to " + role + " needs a transaction");
        }
        String caller =
                jdbc.sql("SELECT current_setting('role')").query(String.class).single();
        switchTo(role);
        T result;
        try {
            result = work.get();
        } catch (RuntimeException | Error failure) {
            restore(caller, failure);
            throw failure;
        }
        switchTo(caller);
        return result;
    }

    /** Restores the caller's role after a failure, which an aborted transaction may refuse. */
    private void restore(String caller, Throwable failure) {
        try {
            switchTo(caller);
        } catch (RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    // set_config('role', …, true) is SET LOCAL ROLE taking the role as a parameter.
    private void switchTo(String role) {
        jdbc.sql("SELECT set_config('role', ?, true)")
                .param(role)
                .query(String.class)
                .single();
    }
}
