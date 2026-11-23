package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The role the service logs in as (tenancy design, section 5.6). */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LoginRoleTest {

    private static final List<String> MODULE_ROLES = List.of(
            "finaxis_tenancy",
            "finaxis_access",
            "finaxis_ledger",
            "finaxis_ledger_jobs",
            "finaxis_idempotency",
            "finaxis_idempotency_jobs",
            "finaxis_billing",
            "finaxis_risk",
            "finaxis_risk_jobs");

    private final JdbcClient jdbc;

    @Autowired
    LoginRoleTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void isTheRoleTheServiceLogsInAs() {
        assertThat(jdbc.sql("SELECT session_user").query(String.class).single()).isEqualTo("finaxis_app");
    }

    @Test
    void reachesNoModuleTableOutsideAModulesApi() {
        assertThatThrownBy(() -> jdbc.sql("SELECT count(*) FROM tenancy.organizations")
                        .query(Long.class)
                        .single())
                .rootCause()
                .hasMessageContaining("permission denied for schema tenancy");
    }

    @Test
    void mayActAsEveryModuleRole() {
        assertThat(MODULE_ROLES)
                .allSatisfy(
                        role -> assertThat(loginRoleHas(role, "SET")).as(role).isTrue());
    }

    @Test
    void inheritsNoModuleRolesPrivileges() {
        assertThat(MODULE_ROLES)
                .allSatisfy(
                        role -> assertThat(loginRoleHas(role, "USAGE")).as(role).isFalse());
    }

    @Test
    void limitsHowLongTheServiceWaits() {
        assertThat(settingsOf("finaxis_app"))
                .containsExactlyInAnyOrder("lock_timeout=2s", "statement_timeout=5s", "transaction_timeout=30s");
    }

    @Test
    void leavesTheModuleRolesNoSettingOfTheirOwn() {
        assertThat(MODULE_ROLES)
                .allSatisfy(role -> assertThat(settingsOf(role)).as(role).isEmpty());
    }

    // USAGE: the role's privileges are inherited; SET: SET ROLE may switch to it.
    private boolean loginRoleHas(String role, String privilege) {
        return jdbc.sql("SELECT pg_has_role('finaxis_app', ?, ?)")
                .params(role, privilege)
                .query(Boolean.class)
                .single();
    }

    // Settings a role applies when it logs in, whatever the database.
    private List<@Nullable String> settingsOf(String role) {
        return jdbc.sql("""
                        SELECT unnest(setconfig)
                          FROM pg_db_role_setting
                         WHERE setrole = ?::regrole
                        """).param(role).query(String.class).list();
    }
}
