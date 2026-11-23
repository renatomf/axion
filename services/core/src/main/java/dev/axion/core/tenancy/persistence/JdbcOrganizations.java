package dev.axion.core.tenancy.persistence;

import dev.axion.core.shared.Environment;
import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.OrganizationId;
import dev.axion.core.tenancy.Role;
import dev.axion.core.tenancy.TenantStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Inserts only: the axion_tenancy role may not update or delete an organization, tenant or member.
@Repository
public class JdbcOrganizations {

    private final JdbcClient jdbc;

    public JdbcOrganizations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Customer organizations only; the platform's comes from a migration.
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
