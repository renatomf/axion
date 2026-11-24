/**
 * Charges, the payments they receive and their refunds (billing design). Other modules use only the
 * types in this package and, to write them as the public API does, those of {@code representation}.
 */
@ApplicationModule(displayName = "Billing")
@NullMarked
package dev.axion.core.billing;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
