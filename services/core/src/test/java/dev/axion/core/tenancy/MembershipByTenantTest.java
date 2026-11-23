package dev.axion.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link TenancyApi#membership} against a real PostgreSQL. Each test uses a new Keycloak subject,
 * so it never meets another test's members.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class MembershipByTenantTest {

    private final TenancyApi tenancy;
    private final JdbcClient owner;

    @Autowired
    MembershipByTenantTest(TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @Test
    void findsTheMembershipThroughEitherTenantOfTheOrganization() {
        var subject = newSubject();
        var created = tenancy.createOrganization("Acme", subject);

        var throughTest = tenancy.membership(subject, created.test()).orElseThrow();
        var throughLive = tenancy.membership(subject, created.live()).orElseThrow();

        assertThat(throughTest).isEqualTo(throughLive);
        assertThat(throughTest.member()).isEqualTo(created.admin());
        assertThat(throughTest.organization()).isEqualTo(created.organization());
        assertThat(throughTest.roles()).containsExactly(Role.ADMIN);
        assertThat(throughTest.tenants()).extracting(TenantView::id).containsExactly(created.test(), created.live());
    }

    @Test
    void findsNoMembershipInAnotherPersonsOrganization() {
        var others = tenancy.createOrganization("Someone else's", newSubject());

        assertThat(tenancy.membership(newSubject(), others.test())).isEmpty();
    }

    @Test
    void findsNoMembershipInAnotherOrganizationOfTheSamePerson() {
        var subject = newSubject();
        tenancy.createOrganization("Acme", subject);
        var others = tenancy.createOrganization("Someone else's", newSubject());

        assertThat(tenancy.membership(subject, others.live())).isEmpty();
    }

    @Test
    void findsNoMembershipForATenantThatDoesNotExist() {
        var subject = newSubject();
        tenancy.createOrganization("Acme", subject);

        assertThat(tenancy.membership(subject, new TenantId(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void findsTheMembershipOfASuspendedTenantWithItsStatus() {
        var subject = newSubject();
        var created = tenancy.createOrganization("Acme", subject);
        owner.sql("UPDATE tenancy.tenants SET status = 'SUSPENDED' WHERE id = ?")
                .param(created.live().value())
                .update();

        var membership = tenancy.membership(subject, created.live()).orElseThrow();

        assertThat(membership.tenants())
                .filteredOn(tenant -> tenant.id().equals(created.live()))
                .extracting(TenantView::status)
                .containsExactly(TenantStatus.SUSPENDED);
    }

    private static String newSubject() {
        return UUID.randomUUID().toString();
    }
}
