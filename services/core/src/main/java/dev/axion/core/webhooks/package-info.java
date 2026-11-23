/**
 * Webhooks (events and webhooks design, sections 6–8): the endpoints tenants register, through
 * {@link dev.axion.core.webhooks.WebhooksApi}, and the rules that keep the core's requests to them
 * off its own network.
 *
 * <p>The module receives domain events and nothing else from other modules: it never calls one, so
 * it cannot slow down what publishes them.
 *
 * <ul>
 *   <li>{@code application}: the implementation of {@code WebhooksApi}, which owns the transactions,
 *       and the encryption of signing secrets;
 *   <li>{@code domain}: which URLs and addresses the core may send to, the event types an endpoint
 *       may receive and the format of secrets, in plain Java;
 *   <li>{@code persistence}: the module's SQL.
 * </ul>
 */
@ApplicationModule(displayName = "Webhooks", allowedDependencies = "shared")
@NullMarked
package dev.axion.core.webhooks;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
