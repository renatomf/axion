/**
 * Who uses the platform: organizations, their tenants, members and roles (tenancy design). Other
 * modules use only {@link TenancyApi} and the types in this package.
 */
@ApplicationModule(displayName = "Tenancy")
@NullMarked
package dev.axion.core.tenancy;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
