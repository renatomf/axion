package dev.finaxis.core.access;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static dev.finaxis.core.tenancy.Permission.CHARGES_WRITE;
import static dev.finaxis.core.tenancy.Permission.REFUNDS_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.domain.ApiKeySecret;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.Role;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AccessApi#roll} against a real PostgreSQL. Each test creates its own organization, so it
 * never meets another test's keys.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class RollApiKeyTest {

    private static final Duration DAY = Duration.ofDays(1);

    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient jdbc;
    private NewOrganization organization;

    @Autowired
    RollApiKeyTest(AccessApi access, TenancyApi tenancy, JdbcClient jdbc) {
        this.access = access;
        this.tenancy = tenancy;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void issuesAReplacementWithTheSameNameAndScopes() {
        var old = issued(Environment.LIVE, Set.of(CHARGES_READ, CHARGES_WRITE));

        var rolled = rolled(access.roll(admin(Environment.LIVE), old.id(), DAY));

        var replacement = rolled.replacement();
        assertThat(replacement.id()).isNotEqualTo(old.id());
        assertThat(replacement.name()).isEqualTo("Checkout");
        assertThat(replacement.scopes()).containsExactlyInAnyOrder(CHARGES_READ, CHARGES_WRITE);
        assertThat(replacement.secret()).isNotEqualTo(old.secret());
        assertThat(ApiKeySecret.parse(replacement.secret()).orElseThrow().environment())
                .isEqualTo(Environment.LIVE);
    }

    @Test
    void endsTheOldKeyAfterTheGracePeriod() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));

        var rolled = rolled(access.roll(admin(Environment.TEST), old.id(), DAY));

        assertThat(rolled.rolled().id()).isEqualTo(old.id());
        assertThat(rolled.rolled().expiresAt())
                .hasValue(rolled.replacement().createdAt().plus(DAY));
        assertThat(rolled.rolled().revokedAt()).isEmpty();
        assertThat(access.keys(organization.test())).hasSize(2).contains(rolled.rolled());
    }

    @Test
    void endsTheOldKeyAtOnceWithoutAGracePeriod() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));

        var rolled = rolled(access.roll(admin(Environment.TEST), old.id(), Duration.ZERO));

        assertThat(rolled.rolled().expiresAt()).hasValue(rolled.replacement().createdAt());
    }

    @Test
    void recordsTheMemberWhoRolledAsTheIssuer() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));
        var developer = new MemberId(UUID.randomUUID());

        var rolled = rolled(access.roll(caller(Environment.TEST, developer, Role.DEVELOPER), old.id(), DAY));

        var createdBy = jdbc.sql("SELECT created_by FROM access.api_keys WHERE id = ?")
                .param(rolled.replacement().id().value())
                .query(UUID.class)
                .single();
        assertThat(createdBy).isEqualTo(developer.value());
    }

    @Test
    void treatsAKeyOfAnotherTenantAsUnknown() {
        var live = issued(Environment.LIVE, Set.of(CHARGES_READ));

        assertThat(access.roll(admin(Environment.TEST), live.id(), DAY)).isEqualTo(new RollResult.KeyNotFound());
        assertThat(access.keys(organization.live()))
                .singleElement()
                .satisfies(key -> assertThat(key.expiresAt()).isEmpty());
        assertThat(access.keys(organization.test())).isEmpty();
    }

    @Test
    void findsNothingToRollForAnUnknownKey() {
        assertThat(access.roll(admin(Environment.TEST), new ApiKeyId(UUID.randomUUID()), DAY))
                .isEqualTo(new RollResult.KeyNotFound());
    }

    @Test
    void acceptsAGracePeriodOfUpToSevenDays() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));

        assertThat(access.roll(admin(Environment.TEST), old.id(), AccessApi.MAX_ROLL_GRACE))
                .isInstanceOf(RollResult.Rolled.class);
    }

    @Test
    void rejectsAGracePeriodOutOfBounds() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));

        assertThatThrownBy(() -> access.roll(admin(Environment.TEST), old.id(), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> access.roll(admin(Environment.TEST), old.id(), AccessApi.MAX_ROLL_GRACE.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(access.keys(organization.test())).hasSize(1);
    }

    @Test
    void refusesToRollARevokedKey() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));
        access.revoke(admin(Environment.TEST), old.id());

        assertThat(access.roll(admin(Environment.TEST), old.id(), DAY)).isEqualTo(new RollResult.KeyNotRollable());
        assertThat(access.keys(organization.test())).hasSize(1);
    }

    /** Rolling it again would give it a second replacement and could extend its grace period. */
    @Test
    void refusesToRollAKeyInItsGracePeriod() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));
        var first = rolled(access.roll(admin(Environment.TEST), old.id(), DAY));

        assertThat(access.roll(admin(Environment.TEST), old.id(), AccessApi.MAX_ROLL_GRACE))
                .isEqualTo(new RollResult.KeyNotRollable());
        assertThat(access.keys(organization.test())).hasSize(2).contains(first.rolled());
    }

    @Test
    void refusesToRollAKeyWhoseGracePeriodHasEnded() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));
        access.roll(admin(Environment.TEST), old.id(), Duration.ZERO);

        assertThat(access.roll(admin(Environment.TEST), old.id(), DAY)).isEqualTo(new RollResult.KeyNotRollable());
        assertThat(access.keys(organization.test())).hasSize(2);
    }

    /** The replacement is a key like any other, so it can be rolled in turn. */
    @Test
    void rollsTheReplacementInTurn() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));
        var first = rolled(access.roll(admin(Environment.TEST), old.id(), DAY));

        assertThat(access.roll(admin(Environment.TEST), first.replacement().id(), DAY))
                .isInstanceOf(RollResult.Rolled.class);
    }

    /** The admin who issued the key had refunds:write; the developer who rolls it does not. */
    @Test
    void refusesScopesTheMemberWhoRollsLacks() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ, REFUNDS_WRITE));
        var developer = caller(Environment.TEST, new MemberId(UUID.randomUUID()), Role.DEVELOPER);

        assertThat(access.roll(developer, old.id(), DAY))
                .isEqualTo(new RollResult.ScopesNotHeld(Set.of(REFUNDS_WRITE)));
        assertThat(access.keys(organization.test()))
                .singleElement()
                .satisfies(key -> assertThat(key.expiresAt()).isEmpty());
    }

    @Test
    void refusesAGracePeriodThatWouldPassTheLimit() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));
        for (int i = 1; i < AccessApi.MAX_ACTIVE_KEYS; i++) {
            issued(Environment.TEST, Set.of(CHARGES_READ));
        }

        assertThat(access.roll(admin(Environment.TEST), old.id(), DAY))
                .isEqualTo(new RollResult.LimitReached(AccessApi.MAX_ACTIVE_KEYS));
        assertThat(access.keys(organization.test()))
                .hasSize(AccessApi.MAX_ACTIVE_KEYS)
                .allSatisfy(key -> assertThat(key.expiresAt()).isEmpty());
    }

    /** Without a grace period, the replacement takes the old key's place instead of joining it. */
    @Test
    void rollsWithoutAGracePeriodAtTheLimit() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));
        for (int i = 1; i < AccessApi.MAX_ACTIVE_KEYS; i++) {
            issued(Environment.TEST, Set.of(CHARGES_READ));
        }

        assertThat(access.roll(admin(Environment.TEST), old.id(), Duration.ZERO))
                .isInstanceOf(RollResult.Rolled.class);
    }

    /** During the grace period both keys work, so both count towards the limit. */
    @Test
    void countsTheOldKeyDuringItsGracePeriod() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));
        for (int i = 2; i < AccessApi.MAX_ACTIVE_KEYS; i++) {
            issued(Environment.TEST, Set.of(CHARGES_READ));
        }
        rolled(access.roll(admin(Environment.TEST), old.id(), DAY));

        assertThat(access.issue(admin(Environment.TEST), "Checkout", Set.of(CHARGES_READ)))
                .isEqualTo(new IssueResult.LimitReached(AccessApi.MAX_ACTIVE_KEYS));
    }

    /**
     * Acting as {@code finaxis_access} proves the code needs no privilege beyond the role's grants
     * (migration {@code V10}): the old key's update touches only {@code expires_at}. The test's
     * transaction is rolled back, so it leaves no rows behind.
     */
    @Test
    @Transactional
    void needsNoMoreThanTheAccessRolesGrants() {
        var old = issued(Environment.TEST, Set.of(CHARGES_READ));
        jdbc.sql("SET LOCAL ROLE finaxis_access").update();

        assertThat(access.roll(admin(Environment.TEST), old.id(), DAY)).isInstanceOf(RollResult.Rolled.class);
    }

    /** The caller tenant resolution would build for the organization's admin. */
    private MemberCaller admin(Environment environment) {
        return caller(environment, organization.admin(), Role.ADMIN);
    }

    private MemberCaller caller(Environment environment, MemberId member, Role role) {
        TenantId tenant = environment == Environment.TEST ? organization.test() : organization.live();
        return new MemberCaller(tenant, environment, member, Role.permissionsOf(Set.of(role)));
    }

    private IssuedApiKey issued(Environment environment, Set<Permission> scopes) {
        var result = access.issue(admin(environment), "Checkout", scopes);
        assertThat(result).isInstanceOf(IssueResult.Issued.class);
        return ((IssueResult.Issued) result).key();
    }

    private static RollResult.Rolled rolled(RollResult result) {
        assertThat(result).isInstanceOf(RollResult.Rolled.class);
        return (RollResult.Rolled) result;
    }
}
