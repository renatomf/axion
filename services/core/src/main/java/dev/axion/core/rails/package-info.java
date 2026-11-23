/**
 * The anti-corruption layer between the core and the Pix rails (billing design, section 7): it
 * receives the rails' signed credit notifications and records them in an inbox. The rails' format
 * stops at this module.
 */
@ApplicationModule(displayName = "Rails", allowedDependencies = "shared")
@NullMarked
package dev.axion.core.rails;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
