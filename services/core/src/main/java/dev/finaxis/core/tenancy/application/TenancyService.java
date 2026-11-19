package dev.finaxis.core.tenancy.application;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.Membership;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.OrganizationId;
import dev.finaxis.core.tenancy.Role;
import dev.finaxis.core.tenancy.TenancyApi;
import dev.finaxis.core.tenancy.TenantStatus;
import dev.finaxis.core.tenancy.TenantView;
import dev.finaxis.core.tenancy.persistence.JdbcMembers;
import dev.finaxis.core.tenancy.persistence.JdbcMembers.MemberRow;
import dev.finaxis.core.tenancy.persistence.JdbcOrganizations;
import dev.finaxis.core.tenancy.persistence.JdbcTenants;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenancy module's implementation of {@link TenancyApi}. Other modules depend on the
 * interface; Spring passes them this bean, the only implementation.
 */
@Service
public class TenancyService implements TenancyApi {

    /** The fixed IDs the migration {@code V7__create_platform_organization} gives the platform tenants. */
    private static final TenantId PLATFORM_TEST = new TenantId(UUID.fromString("00000000-0000-7000-8000-000000000002"));

    private static final TenantId PLATFORM_LIVE = new TenantId(UUID.fromString("00000000-0000-7000-8000-000000000003"));

    private final JdbcOrganizations organizations;
    private final JdbcTenants tenants;
    private final JdbcMembers members;
    private final UuidV7Generator ids;

    public TenancyService(JdbcOrganizations organizations, JdbcTenants tenants, JdbcMembers members, Clock clock) {
        this.organizations = organizations;
        this.tenants = tenants;
        this.members = members;
        this.ids = new UuidV7Generator(clock);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code @Transactional} joins the caller's transaction, or starts one if there is none: the
     * five inserts commit together or not at all. The schema checks the lengths again; checking
     * them here first gives the caller a clear error instead of a constraint name.
     */
    @Override
    @Transactional
    public NewOrganization createOrganization(String name, String adminSubject) {
        requireText("Organization name", name, MAX_NAME_LENGTH);
        requireText("Admin subject", adminSubject, MAX_SUBJECT_LENGTH);
        var created = new NewOrganization(
                new OrganizationId(ids.next()),
                new TenantId(ids.next()),
                new TenantId(ids.next()),
                new MemberId(ids.next()));
        organizations.insertOrganization(created.organization(), name);
        organizations.insertTenant(created.test(), created.organization(), Environment.TEST, TenantStatus.ACTIVE);
        organizations.insertTenant(created.live(), created.organization(), Environment.LIVE, TenantStatus.ACTIVE);
        organizations.insertMember(created.admin(), created.organization(), adminSubject);
        organizations.insertRole(created.admin(), Role.ADMIN);
        return created;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code readOnly = true} tells Spring, and through the driver PostgreSQL, that the call
     * writes nothing; a write by mistake fails instead of committing.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<TenantView> tenant(TenantId tenant) {
        return tenants.find(tenant);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two queries, whatever the number of organizations: the person's members, then the tenants
     * of all their organizations at once. Both run in one read-only transaction, so they see the
     * same snapshot of the directory.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Membership> memberships(String userSubject) {
        List<MemberRow> rows = members.findBySubject(userSubject);
        Map<OrganizationId, List<TenantView>> tenantsByOrganization =
                tenants
                        .findByOrganizations(
                                rows.stream().map(MemberRow::organization).toList())
                        .stream()
                        .sorted(Comparator.comparing(TenantView::environment))
                        .collect(Collectors.groupingBy(TenantView::organization));
        return rows.stream()
                .map(row -> new Membership(
                        row.id(),
                        row.organization(),
                        row.organizationName(),
                        row.roles(),
                        tenantsByOrganization.getOrDefault(row.organization(), List.of())))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The IDs are fixed by a migration, so no query is needed; a test checks that they name the
     * platform's tenants.
     */
    @Override
    public TenantId platformTenant(Environment environment) {
        return switch (environment) {
            case TEST -> PLATFORM_TEST;
            case LIVE -> PLATFORM_LIVE;
        };
    }

    /**
     * Rejects blank or overlong text. The length counts characters as PostgreSQL's {@code
     * char_length} does, by code point, so an emoji counts once, not as the two {@code char}s Java
     * stores it in.
     */
    private static void requireText(String what, String text, int maxLength) {
        if (text.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        int length = text.codePointCount(0, text.length());
        if (length > maxLength) {
            throw new IllegalArgumentException(what + " must be at most " + maxLength + " characters: " + length);
        }
    }
}
