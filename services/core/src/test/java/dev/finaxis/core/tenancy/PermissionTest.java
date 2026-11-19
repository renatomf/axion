package dev.finaxis.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PermissionTest {

    @Test
    void codesAreUniqueAndNameAResourceAndAnAction() {
        assertThat(Arrays.stream(Permission.values()).map(Permission::code))
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[a-z_]+:[a-z]+"));
    }

    @Test
    void everyPermissionIsReadBackFromItsCode() {
        assertThat(Permission.values()).allMatch(permission -> Permission.fromCode(permission.code()) == permission);
    }

    @Test
    void rejectsAnUnknownCode() {
        assertThatThrownBy(() -> Permission.fromCode("CHARGES_READ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown permission: CHARGES_READ");
    }

    @Test
    void humanOnlyPermissionsAreNeverApiKeyScopes() {
        assertThat(Arrays.stream(Permission.values()).filter(permission -> !permission.isApiKeyScope()))
                .containsExactlyInAnyOrder(
                        Permission.API_KEYS_MANAGE, Permission.MEMBERS_MANAGE, Permission.PAYOUTS_APPROVE);
    }
}
