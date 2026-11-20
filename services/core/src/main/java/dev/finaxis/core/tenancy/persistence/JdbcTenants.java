package dev.finaxis.core.tenancy.persistence;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.OrganizationId;
import dev.finaxis.core.tenancy.TenantStatus;
import dev.finaxis.core.tenancy.TenantView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenants {

    private final JdbcClient jdbc;

    public JdbcTenants(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantView> find(TenantId id) {
        return jdbc.sql("""
                        SELECT id, organization_id, environment, status
                          FROM tenancy.tenants
                         WHERE id = :id
                        """).param("id", id.value()).query(JdbcTenants::tenant).optional();
    }

    public List<TenantView> findByOrganizations(Collection<OrganizationId> organizations) {
        if (organizations.isEmpty()) {
            // "IN ()" is not valid SQL; no organizations have no tenants.
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, organization_id, environment, status
                          FROM tenancy.tenants
                         WHERE organization_id IN (:organizations)
                        """)
                .param(
                        "organizations",
                        organizations.stream().map(OrganizationId::value).toList())
                .query(JdbcTenants::tenant)
                .list();
    }

    private static TenantView tenant(ResultSet row, int rowNumber) throws SQLException {
        return new TenantView(
                new TenantId(row.getObject("id", UUID.class)),
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                Environment.valueOf(row.getString("environment")),
                TenantStatus.valueOf(row.getString("status")));
    }
}
