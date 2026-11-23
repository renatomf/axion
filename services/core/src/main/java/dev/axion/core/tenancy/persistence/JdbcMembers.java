package dev.axion.core.tenancy.persistence;

import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.OrganizationId;
import dev.axion.core.tenancy.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMembers {

    public record MemberRow(MemberId id, OrganizationId organization, String organizationName, Set<Role> roles) {}

    private final JdbcClient jdbc;

    public JdbcMembers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // o.id breaks ties between organizations of the same name, so the order is stable.
    public List<MemberRow> findBySubject(String userSubject) {
        return jdbc.sql("""
                        SELECT m.id, m.organization_id, o.name,
                               ARRAY(SELECT r.role FROM tenancy.member_roles r WHERE r.member_id = m.id) AS roles
                          FROM tenancy.members m
                          JOIN tenancy.organizations o ON o.id = m.organization_id
                         WHERE m.user_subject = :userSubject
                         ORDER BY o.name, o.id
                        """)
                .param("userSubject", userSubject)
                .query(JdbcMembers::member)
                .list();
    }

    public Optional<MemberRow> findBySubjectAndTenant(String userSubject, TenantId tenant) {
        return jdbc.sql("""
                        SELECT m.id, m.organization_id, o.name,
                               ARRAY(SELECT r.role FROM tenancy.member_roles r WHERE r.member_id = m.id) AS roles
                          FROM tenancy.tenants t
                          JOIN tenancy.members m ON m.organization_id = t.organization_id
                          JOIN tenancy.organizations o ON o.id = m.organization_id
                         WHERE t.id = :tenant
                           AND m.user_subject = :userSubject
                        """)
                .param("tenant", tenant.value())
                .param("userSubject", userSubject)
                .query(JdbcMembers::member)
                .optional();
    }

    private static MemberRow member(ResultSet row, int rowNumber) throws SQLException {
        String[] roles = (String[]) row.getArray("roles").getArray();
        return new MemberRow(
                new MemberId(row.getObject("id", UUID.class)),
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                row.getString("name"),
                Arrays.stream(roles).map(Role::valueOf).collect(Collectors.toUnmodifiableSet()));
    }
}
