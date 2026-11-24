/**
 * The audit trail (events and webhooks design, section 9): one record per security-relevant action,
 * in a hash chain per tenant whose tampering {@link dev.axion.core.audit.AuditApi#verify} detects.
 *
 * <ul>
 *   <li>{@code application}: appending to a tenant's chain and verifying one, which own the transactions;
 *   <li>{@code domain}: a record's fields and the hash that chains it, in plain Java;
 *   <li>{@code persistence}: the module's SQL.
 * </ul>
 */
@ApplicationModule(
        displayName = "Audit",
        allowedDependencies = {"shared", "access", "tenancy"})
@NullMarked
package dev.axion.core.audit;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
