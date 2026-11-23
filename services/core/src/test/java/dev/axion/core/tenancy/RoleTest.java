package dev.axion.core.tenancy;

import static dev.axion.core.tenancy.Permission.API_KEYS_MANAGE;
import static dev.axion.core.tenancy.Permission.BALANCES_READ;
import static dev.axion.core.tenancy.Permission.CHARGES_READ;
import static dev.axion.core.tenancy.Permission.CHARGES_WRITE;
import static dev.axion.core.tenancy.Permission.MEMBERS_MANAGE;
import static dev.axion.core.tenancy.Permission.PAYOUTS_APPROVE;
import static dev.axion.core.tenancy.Permission.REFUNDS_WRITE;
import static dev.axion.core.tenancy.Permission.WEBHOOKS_MANAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The role matrix of the tenancy design, section 3.3, one row per role. */
class RoleTest {

    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of(
                        Role.ADMIN,
                        List.of(
                                CHARGES_READ,
                                CHARGES_WRITE,
                                REFUNDS_WRITE,
                                BALANCES_READ,
                                WEBHOOKS_MANAGE,
                                API_KEYS_MANAGE,
                                MEMBERS_MANAGE)),
                Arguments.of(Role.FINANCE, List.of(CHARGES_READ, CHARGES_WRITE, REFUNDS_WRITE, BALANCES_READ)),
                Arguments.of(Role.APPROVER, List.of(CHARGES_READ, BALANCES_READ, PAYOUTS_APPROVE)),
                Arguments.of(
                        Role.DEVELOPER,
                        List.of(CHARGES_READ, CHARGES_WRITE, BALANCES_READ, WEBHOOKS_MANAGE, API_KEYS_MANAGE)),
                Arguments.of(Role.READ_ONLY, List.of(CHARGES_READ, BALANCES_READ)));
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void grantsExactlyThePermissionsOfTheDesign(Role role, List<Permission> expected) {
        assertThat(role.permissions()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void coversEveryRole() {
        assertThat(matrix().map(arguments -> arguments.get()[0])).containsExactlyInAnyOrder((Object[]) Role.values());
    }

    @Test
    void anAdminDoesNotApprovePayouts() {
        assertThat(Role.ADMIN.permissions()).doesNotContain(PAYOUTS_APPROVE);
    }

    @Test
    void onlyAnAdminManagesMembers() {
        assertThat(Stream.of(Role.values()).filter(role -> role.permissions().contains(MEMBERS_MANAGE)))
                .containsExactly(Role.ADMIN);
    }

    @Test
    void aMemberHasTheUnionOfTheirRolesPermissions() {
        assertThat(Role.permissionsOf(List.of(Role.APPROVER, Role.DEVELOPER)))
                .containsExactlyInAnyOrder(
                        CHARGES_READ, CHARGES_WRITE, BALANCES_READ, WEBHOOKS_MANAGE, API_KEYS_MANAGE, PAYOUTS_APPROVE);
    }

    @Test
    void aMemberWithNoRolesHasNoPermissions() {
        assertThat(Role.permissionsOf(List.of())).isEmpty();
    }

    @Test
    void permissionSetsCannotBeChanged() {
        assertThatThrownBy(() -> Role.READ_ONLY.permissions().add(MEMBERS_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Role.permissionsOf(List.of(Role.READ_ONLY)).add(MEMBERS_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
