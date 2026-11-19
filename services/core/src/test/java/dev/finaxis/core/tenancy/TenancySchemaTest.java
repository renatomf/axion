package dev.finaxis.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenancy tables and the rules the database enforces on them (tenancy design, section 3.4),
 * checked against a real PostgreSQL.
 *
 * <p>Every test runs inside one transaction that Spring's test support rolls back when the test
 * ends, so it leaves no rows behind.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class TenancySchemaTest {

    private final JdbcClient jdbc;

    @Autowired
    TenancySchemaTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void rejectsAnOrganizationWithoutAName() {
        assertThatThrownBy(() -> insertOrganization(""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("organizations_name_check");
    }

    @Test
    void createsThePlatformOrganizationWithOneTenantPerEnvironment() {
        var tenants = jdbc.sql("""
                        SELECT t.id || ' ' || t.environment || ' ' || t.status
                          FROM tenancy.tenants t
                          JOIN tenancy.organizations o ON o.id = t.organization_id
                         WHERE o.is_platform
                        """).query(String.class).list();

        assertThat(tenants)
                .containsExactlyInAnyOrder(
                        "00000000-0000-7000-8000-000000000002 TEST ACTIVE",
                        "00000000-0000-7000-8000-000000000003 LIVE ACTIVE");
    }

    @Test
    void rejectsASecondPlatformOrganization() {
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO tenancy.organizations (id, name, is_platform) VALUES (?, 'Other', true)")
                        .param(UUID.randomUUID())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("organizations_one_platform_idx");
    }

    @Test
    void rejectsAnUnknownEnvironment() {
        var organization = insertOrganization("Acme");

        assertThatThrownBy(() -> insertTenant(organization, "STAGING"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("tenants_environment_check");
    }

    @Test
    void rejectsASecondTenantForTheSameEnvironment() {
        var organization = insertOrganization("Acme");
        insertTenant(organization, "LIVE");

        assertThatThrownBy(() -> insertTenant(organization, "LIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("tenants_organization_id_environment_key");
    }

    @Test
    void rejectsTheSameUserTwiceInOneOrganization() {
        var organization = insertOrganization("Acme");
        insertMember(organization, "user-1");

        assertThatThrownBy(() -> insertMember(organization, "user-1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("members_organization_id_user_subject_key");
    }

    @Test
    void rejectsAnUnknownRole() {
        var member = insertMember(insertOrganization("Acme"), "user-1");

        assertThatThrownBy(() -> insertRole(member, "OWNER"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("member_roles_role_check");
    }

    private UUID insertOrganization(String name) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenancy.organizations (id, name, is_platform) VALUES (?, ?, false)")
                .params(id, name)
                .update();
        return id;
    }

    private UUID insertTenant(UUID organization, String environment) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tenancy.tenants (id, organization_id, environment, status)
                        VALUES (?, ?, ?, 'ACTIVE')
                        """).params(id, organization, environment).update();
        return id;
    }

    private UUID insertMember(UUID organization, String userSubject) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenancy.members (id, organization_id, user_subject) VALUES (?, ?, ?)")
                .params(id, organization, userSubject)
                .update();
        return id;
    }

    private void insertRole(UUID member, String role) {
        jdbc.sql("INSERT INTO tenancy.member_roles (member_id, role) VALUES (?, ?)")
                .params(member, role)
                .update();
    }
}
