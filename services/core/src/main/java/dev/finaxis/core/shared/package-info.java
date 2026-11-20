/**
 * The shared kernel: the few types every module agrees on, such as {@code Money} and identifiers.
 * Types only, depending on no module; {@code Type.OPEN} makes them all visible to every module.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
@NullMarked
package dev.finaxis.core.shared;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
