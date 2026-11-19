/**
 * The directory of who uses the platform: organizations, their tenants, members and roles (tenancy
 * and access design, {@code docs/design-docs/2026-11-19-tenancy-and-access.md}).
 *
 * <p>Other modules use it only through the types in this package, such as {@link Role} and {@link
 * Permission}; Spring Modulith hides every subpackage from them.
 */
@ApplicationModule(displayName = "Tenancy")
@NullMarked
package dev.finaxis.core.tenancy;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
