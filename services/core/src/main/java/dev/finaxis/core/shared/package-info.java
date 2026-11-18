/**
 * The shared kernel: the few types every module agrees on, such as {@code Money}, identifiers, the
 * business-day calendar and the tenant context.
 *
 * <p>It holds types only: no endpoints, persistence or events, and it depends on no other module.
 * Anything added here is a contract with every module, so it stays small.
 *
 * <p>Spring Modulith treats each direct subpackage of {@code dev.finaxis.core} as an application
 * module and, by default, lets other modules use only the types in the module's own package, not its
 * subpackages. {@code Type.OPEN} makes every type here visible to every module, because the whole
 * kernel is meant to be shared.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
@NullMarked
package dev.finaxis.core.shared;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
