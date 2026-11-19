package dev.finaxis.core.tenancy.persistence;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.OrganizationId;
import dev.finaxis.core.tenancy.TenantStatus;
import dev.finaxis.core.tenancy.TenantView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads tenants from {@code tenancy.tenants} (tenancy design, section 3.4).
 *
 * <p>Runs in the caller's transaction: {@code JdbcClient} uses the connection Spring has bound to
 * the current transaction, if there is one.
 */
@Repository
public class JdbcTenants {

    private final JdbcClient jdbc;

    public JdbcTenants(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The tenant with this ID, if there is one. */
    public Optional<TenantView> find(TenantId id) {
        return jdbc.sql("""
                        SELECT id, organization_id, environment, status
                          FROM tenancy.tenants
                         WHERE id = :id
                        """).param("id", id.value()).query(JdbcTenants::tenant).optional();
    }

    private static TenantView tenant(ResultSet row, int rowNumber) throws SQLException {
        return new TenantView(
                new TenantId(row.getObject("id", UUID.class)),
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                Environment.valueOf(row.getString("environment")),
                TenantStatus.valueOf(row.getString("status")));
    }
}
