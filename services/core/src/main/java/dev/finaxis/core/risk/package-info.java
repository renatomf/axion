/**
 * Decides, before a charge exists, whether its tenant may create it (billing design, section 6).
 * Other modules use only {@link RiskApi} and the types in this package.
 */
@ApplicationModule(displayName = "Risk", allowedDependencies = "shared")
@NullMarked
package dev.finaxis.core.risk;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
