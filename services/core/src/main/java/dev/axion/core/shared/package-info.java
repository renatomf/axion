/**
 * The shared kernel: the few types every module agrees on, such as {@code Money} and identifiers,
 * and the helpers every module uses the same way, such as {@code IdempotentConsumer}. It depends on
 * no module; {@code Type.OPEN} makes it all visible to every module.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
@NullMarked
package dev.axion.core.shared;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
