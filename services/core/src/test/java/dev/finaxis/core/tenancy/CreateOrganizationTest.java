package dev.finaxis.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link TenancyApi#createOrganization} against a real PostgreSQL. Each test uses a new Keycloak
 * subject, so it never meets another test's members.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class CreateOrganizationTest {

    private final TenancyApi tenancy;
    private final JdbcClient owner;

    @Autowired
    CreateOrganizationTest(TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @Test
    void createsOneActiveTenantPerEnvironment() {
        var created = tenancy.createOrganization("Acme", newSubject());

        var test = tenancy.tenant(created.test()).orElseThrow();
        var live = tenancy.tenant(created.live()).orElseThrow();
        assertThat(test)
                .isEqualTo(
                        new TenantView(created.test(), created.organization(), Environment.TEST, TenantStatus.ACTIVE));
        assertThat(live)
                .isEqualTo(
                        new TenantView(created.live(), created.organization(), Environment.LIVE, TenantStatus.ACTIVE));
    }

    @Test
    void makesTheCreatorItsAdmin() {
        var subject = newSubject();

        var created = tenancy.createOrganization("Acme", subject);

        var roles = owner.sql("""
                        SELECT m.user_subject || ' ' || r.role
                          FROM tenancy.members m
                          JOIN tenancy.member_roles r ON r.member_id = m.id
                         WHERE m.organization_id = ?
                        """)
                .param(created.organization().value())
                .query(String.class)
                .list();
        assertThat(roles).containsExactly(subject + " ADMIN");
    }

    @Test
    void letsOnePersonCreateTwoOrganizations() {
        var subject = newSubject();

        var first = tenancy.createOrganization("Acme", subject);
        var second = tenancy.createOrganization("Acme", subject);

        assertThat(first.organization()).isNotEqualTo(second.organization());
        assertThat(first.admin()).isNotEqualTo(second.admin());
    }

    @Test
    void acceptsANameOfOneHundredCharactersCountedByCodePoint() {
        // Each emoji is two Java chars but one PostgreSQL character.
        var name = "💸".repeat(TenancyApi.MAX_NAME_LENGTH);

        var created = tenancy.createOrganization(name, newSubject());

        assertThat(tenancy.tenant(created.test())).isPresent();
    }

    @Test
    void rejectsANameLongerThanOneHundredCharacters() {
        var name = "a".repeat(TenancyApi.MAX_NAME_LENGTH + 1);

        assertThatThrownBy(() -> tenancy.createOrganization(name, newSubject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 100 characters");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> tenancy.createOrganization("   ", newSubject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsABlankAdminSubject() {
        assertThatThrownBy(() -> tenancy.createOrganization("Acme", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    private static String newSubject() {
        return UUID.randomUUID().toString();
    }
}
