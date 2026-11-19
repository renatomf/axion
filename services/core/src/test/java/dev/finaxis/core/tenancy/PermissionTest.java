package dev.finaxis.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

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
    void humanOnlyPermissionsAreNeverApiKeyScopes() {
        assertThat(Arrays.stream(Permission.values()).filter(permission -> !permission.isApiKeyScope()))
                .containsExactlyInAnyOrder(
                        Permission.API_KEYS_MANAGE, Permission.MEMBERS_MANAGE, Permission.PAYOUTS_APPROVE);
    }
}
