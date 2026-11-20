package dev.finaxis.core;

import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The modules' database roles, for tests that prove the code needs no privilege beyond a role's grants. */
public enum DatabaseRole {
    ACCESS,
    TENANCY,
    LEDGER;

    /** Acts as this role until the current transaction ends. */
    public void actAs(JdbcClient jdbc) {
        jdbc.sql("SET LOCAL ROLE finaxis_" + name().toLowerCase(Locale.ROOT)).update();
    }
}
