package dev.axion.core.tenancy;

import static dev.axion.core.tenancy.Permission.API_KEYS_MANAGE;
import static dev.axion.core.tenancy.Permission.BALANCES_READ;
import static dev.axion.core.tenancy.Permission.CHARGES_READ;
import static dev.axion.core.tenancy.Permission.CHARGES_WRITE;
import static dev.axion.core.tenancy.Permission.MEMBERS_MANAGE;
import static dev.axion.core.tenancy.Permission.PAYOUTS_APPROVE;
import static dev.axion.core.tenancy.Permission.REFUNDS_WRITE;
import static dev.axion.core.tenancy.Permission.WEBHOOKS_MANAGE;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * A named set of permissions, for both environments (tenancy design, section 3.3). Fixed in code, so
 * a change is reviewed and tested like any other.
 */
public enum Role {
    ADMIN,
    FINANCE,
    /** Approves payouts and reads; an admin does not approve, so maker and checker stay apart. */
    APPROVER,
    DEVELOPER,
    READ_ONLY;

    /** An exhaustive switch, so a new role does not compile until its permissions are written. */
    public Set<Permission> permissions() {
        return switch (this) {
            case ADMIN ->
                Set.of(
                        CHARGES_READ,
                        CHARGES_WRITE,
                        REFUNDS_WRITE,
                        BALANCES_READ,
                        WEBHOOKS_MANAGE,
                        API_KEYS_MANAGE,
                        MEMBERS_MANAGE);
            case FINANCE -> Set.of(CHARGES_READ, CHARGES_WRITE, REFUNDS_WRITE, BALANCES_READ);
            case APPROVER -> Set.of(CHARGES_READ, BALANCES_READ, PAYOUTS_APPROVE);
            case DEVELOPER -> Set.of(CHARGES_READ, CHARGES_WRITE, BALANCES_READ, WEBHOOKS_MANAGE, API_KEYS_MANAGE);
            case READ_ONLY -> Set.of(CHARGES_READ, BALANCES_READ);
        };
    }

    public static Set<Permission> permissionsOf(Collection<Role> roles) {
        Set<Permission> union = EnumSet.noneOf(Permission.class);
        roles.forEach(role -> union.addAll(role.permissions()));
        return Set.copyOf(union);
    }
}
