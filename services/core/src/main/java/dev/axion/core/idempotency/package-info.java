/**
 * Runs a public API write once per idempotency key, and answers its repeats from the stored response
 * (idempotency design). Other modules use only {@link IdempotencyApi} and the types in this package.
 */
@ApplicationModule(displayName = "Idempotency")
@NullMarked
package dev.axion.core.idempotency;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
