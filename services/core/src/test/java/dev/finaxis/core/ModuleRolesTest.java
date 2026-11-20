package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ModuleRolesTest {

    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    @Autowired
    ModuleRolesTest(ModuleRoles roles, TransactionTemplate transactions, JdbcClient jdbc) {
        this.roles = roles;
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    @Test
    void runsTheWorkAsTheRole() {
        assertThat(inTransaction(() -> roles.call("finaxis_access", this::currentUser)))
                .isEqualTo("finaxis_access");
    }

    @Test
    void restoresTheCallersRoleOnReturn() {
        String sessionUser = inTransaction(this::currentUser);

        assertThat(inTransaction(() -> {
                    roles.call("finaxis_access", this::currentUser);
                    return currentUser();
                }))
                .isEqualTo(sessionUser);
    }

    @Test
    void anotherModuleCalledInsideRestoresTheFirstModulesRole() {
        assertThat(inTransaction(() -> roles.call("finaxis_access", () -> {
                    roles.call("finaxis_tenancy", this::currentUser);
                    return currentUser();
                })))
                .isEqualTo("finaxis_access");
    }

    @Test
    void restoresTheCallersRoleWhenTheWorkFails() {
        String sessionUser = inTransaction(this::currentUser);

        assertThat(inTransaction(() -> {
                    try {
                        roles.call("finaxis_access", () -> {
                            throw new IllegalStateException("work failed");
                        });
                    } catch (IllegalStateException expected) {
                        // The caller carries on in the same transaction.
                    }
                    return currentUser();
                }))
                .isEqualTo(sessionUser);
    }

    @Test
    void theNextTransactionStartsAsTheSessionsRole() {
        String sessionUser = inTransaction(this::currentUser);
        inTransaction(() -> roles.call("finaxis_access", this::currentUser));

        assertThat(inTransaction(this::currentUser)).isEqualTo(sessionUser);
    }

    @Test
    void refusesToSwitchOutsideATransaction() {
        assertThatIllegalStateException().isThrownBy(() -> roles.call("finaxis_access", this::currentUser));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    private String currentUser() {
        return jdbc.sql("SELECT current_user").query(String.class).single();
    }
}
