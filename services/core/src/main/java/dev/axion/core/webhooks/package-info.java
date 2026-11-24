/**
 * Webhooks (events and webhooks design, sections 6–8): the endpoints tenants register, through
 * {@link dev.axion.core.webhooks.WebhooksApi}, the deliveries of the public events they receive,
 * and the rules that keep the core's requests to them off its own network.
 *
 * <p>The module receives domain events and nothing else from other modules: it never calls one, so
 * it cannot slow down what publishes them. It writes them with the events module's catalog, a pure
 * function, to which it contributes how its own public event is written. Its endpoints declare the
 * access module's permissions, as every module's do.
 *
 * <ul>
 *   <li>{@code application}: the implementation of {@code WebhooksApi}, which owns the transactions,
 *       the encryption of signing secrets, and the listener that records deliveries;
 *   <li>{@code domain}: which URLs and addresses the core may send to, the event types an endpoint
 *       may receive, the format of secrets and what an attempt to deliver can come to, in plain
 *       Java;
 *   <li>{@code http}: the HTTP client that sends webhooks, connecting only to checked addresses;
 *   <li>{@code persistence}: the module's SQL;
 *   <li>{@code web}: the module's endpoints, the bodies they return, and its public event written
 *       with them.
 * </ul>
 */
@ApplicationModule(
        displayName = "Webhooks",
        allowedDependencies = {"shared", "web", "access", "tenancy", "billing", "events"})
@NullMarked
package dev.axion.core.webhooks;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
