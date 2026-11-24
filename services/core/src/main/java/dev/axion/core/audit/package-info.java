/**
 * The audit trail (events and webhooks design, section 9): one record per security-relevant action,
 * in a hash chain per tenant, so that a record changed, removed or reordered shows.
 *
 * <ul>
 *   <li>{@code domain}: a record's fields and the hash that chains it, in plain Java.
 * </ul>
 */
@ApplicationModule(displayName = "Audit", allowedDependencies = "shared")
@NullMarked
package dev.axion.core.audit;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
