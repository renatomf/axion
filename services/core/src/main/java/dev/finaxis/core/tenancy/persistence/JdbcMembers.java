package dev.finaxis.core.tenancy.persistence;

import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.OrganizationId;
import dev.finaxis.core.tenancy.Role;
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

/**
 * Reads members and their roles from {@code tenancy.members} and {@code tenancy.member_roles}
 * (tenancy design, section 3.4).
 *
 * <p>Runs in the caller's transaction.
 */
@Repository
public class JdbcMembers {

    /**
     * One member row with its roles and its organization's name.
     *
     * @param id the member
     * @param organization the organization the member belongs to
     * @param organizationName the organization's display name
     * @param roles the member's roles
     */
    public record MemberRow(MemberId id, OrganizationId organization, String organizationName, Set<Role> roles) {}

    private final JdbcClient jdbc;

    public JdbcMembers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every member a person is, one per organization, ordered by organization name and then ID, so
     * the order is stable when two organizations share a name. The index on {@code user_subject}
     * finds the rows; the roles come from a subquery on the primary key of {@code member_roles}.
     */
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

    /**
     * The member a person is in the organization of this tenant, if any. The tenant is found by its
     * primary key and the member by the unique {@code (organization_id, user_subject)} index, so the
     * query reads one row of each table whatever their size.
     */
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
        // A PostgreSQL text[] arrives as a java.sql.Array whose elements are Strings.
        String[] roles = (String[]) row.getArray("roles").getArray();
        return new MemberRow(
                new MemberId(row.getObject("id", UUID.class)),
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                row.getString("name"),
                Arrays.stream(roles).map(Role::valueOf).collect(Collectors.toUnmodifiableSet()));
    }
}
