package dev.finaxis.core.tenancy.persistence;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.OrganizationId;
import dev.finaxis.core.tenancy.Role;
import dev.finaxis.core.tenancy.TenantStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Inserts organizations, their tenants, members and roles (tenancy design, section 3.4). Only the
 * statements the {@code finaxis_tenancy} role is granted are written here: nothing updates or
 * deletes an organization, tenant or member.
 *
 * <p>Runs in the caller's transaction, so the service decides what commits together.
 */
@Repository
public class JdbcOrganizations {

    private final JdbcClient jdbc;

    public JdbcOrganizations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a customer organization; the platform organization comes only from a migration. */
    public void insertOrganization(OrganizationId id, String name) {
        jdbc.sql("INSERT INTO tenancy.organizations (id, name, is_platform) VALUES (:id, :name, false)")
                .param("id", id.value())
                .param("name", name)
                .update();
    }

    public void insertTenant(TenantId id, OrganizationId organization, Environment environment, TenantStatus status) {
        jdbc.sql("""
                        INSERT INTO tenancy.tenants (id, organization_id, environment, status)
                        VALUES (:id, :organization, :environment, :status)
                        """)
                .param("id", id.value())
                .param("organization", organization.value())
                .param("environment", environment.name())
                .param("status", status.name())
                .update();
    }

    public void insertMember(MemberId id, OrganizationId organization, String userSubject) {
        jdbc.sql("""
                        INSERT INTO tenancy.members (id, organization_id, user_subject)
                        VALUES (:id, :organization, :userSubject)
                        """)
                .param("id", id.value())
                .param("organization", organization.value())
                .param("userSubject", userSubject)
                .update();
    }

    public void insertRole(MemberId member, Role role) {
        jdbc.sql("INSERT INTO tenancy.member_roles (member_id, role) VALUES (:member, :role)")
                .param("member", member.value())
                .param("role", role.name())
                .update();
    }
}
