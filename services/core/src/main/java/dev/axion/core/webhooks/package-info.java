/**
 * Webhooks (events and webhooks design, sections 6–8): the endpoints tenants register and the
 * rules that keep the core's requests to them off its own network.
 */
@ApplicationModule(displayName = "Webhooks", allowedDependencies = "shared")
@NullMarked
package dev.axion.core.webhooks;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
