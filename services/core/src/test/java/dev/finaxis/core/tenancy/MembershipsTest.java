package dev.finaxis.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

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
 * {@link TenancyApi#memberships} against a real PostgreSQL. Each test uses a new Keycloak subject,
 * so it never meets another test's members.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class MembershipsTest {

    private final TenancyApi tenancy;
    private final JdbcClient owner;

    @Autowired
    MembershipsTest(TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @Test
    void listsEveryOrganizationOfAPersonByName() {
        var subject = newSubject();
        var beta = tenancy.createOrganization("Beta", subject);
        var acme = tenancy.createOrganization("Acme", subject);
        tenancy.createOrganization("Someone else's", newSubject());

        var memberships = tenancy.memberships(subject);

        assertThat(memberships)
                .extracting(Membership::organization)
                .containsExactly(acme.organization(), beta.organization());
        var first = memberships.getFirst();
        assertThat(first.member()).isEqualTo(acme.admin());
        assertThat(first.organizationName()).isEqualTo("Acme");
        assertThat(first.roles()).containsExactly(Role.ADMIN);
    }

    @Test
    void listsTheTestTenantBeforeTheLiveOne() {
        var subject = newSubject();
        var created = tenancy.createOrganization("Acme", subject);

        var tenants = tenancy.memberships(subject).getFirst().tenants();

        assertThat(tenants)
                .containsExactly(
                        new TenantView(
                                created.test(), created.organization(), "Acme", Environment.TEST, TenantStatus.ACTIVE),
                        new TenantView(
                                created.live(), created.organization(), "Acme", Environment.LIVE, TenantStatus.ACTIVE));
    }

    @Test
    void listsEveryRoleOfAMember() {
        var subject = newSubject();
        var created = tenancy.createOrganization("Acme", subject);
        owner.sql("INSERT INTO tenancy.member_roles (member_id, role) VALUES (?, 'FINANCE')")
                .param(created.admin().value())
                .update();

        assertThat(tenancy.memberships(subject).getFirst().roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.FINANCE);
    }

    @Test
    void findsNoMembershipsForAPersonInNoOrganization() {
        assertThat(tenancy.memberships(newSubject())).isEmpty();
    }

    private static String newSubject() {
        return UUID.randomUUID().toString();
    }
}
