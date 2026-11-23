package dev.axion.core.access;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.TestCaller;
import dev.axion.core.shared.ActorContext;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.Role;
import java.util.Set;

/** Callers and keys for the access module's tests. */
public final class AccessFixtures {

    private AccessFixtures() {}

    /** The caller tenant resolution builds for this member, on the organization's tenant of this environment. */
    public static MemberCaller member(
            NewOrganization organization, Environment environment, MemberId member, Role... roles) {
        TenantId tenant = environment == Environment.TEST ? organization.test() : organization.live();
        return new MemberCaller(tenant, environment, member, Role.permissionsOf(Set.of(roles)));
    }

    public static MemberCaller admin(NewOrganization organization, Environment environment) {
        return member(organization, environment, organization.admin(), Role.ADMIN);
    }

    /**
     * Issues a key as a controller would, with the issuer's tenant bound and the issuer as the actor,
     * failing the test unless it is issued.
     */
    public static IssuedApiKey issue(AccessApi access, MemberCaller issuer, String name, Set<Permission> scopes) {
        IssueResult result = TenantContext.call(
                issuer.tenant(),
                issuer.environment(),
                () -> ActorContext.call(
                        issuer.actor(), TestCaller.CORRELATION_ID, () -> access.issue(issuer, name, scopes)));
        assertThat(result).isInstanceOf(IssueResult.Issued.class);
        return ((IssueResult.Issued) result).key();
    }
}
