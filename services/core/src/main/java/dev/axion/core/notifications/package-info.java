/**
 * Live notifications (events and webhooks design, section 10): the streams a member's console keeps
 * open on a tenant, and the tenant's recent public events, from which a reconnecting stream resumes.
 *
 * <p>The module receives domain events and nothing else from other modules: it never calls one, so
 * it cannot slow down what publishes them. It writes them with the events module's catalog, a pure
 * function. It keeps no table: the stream is a convenience, not a record. Its endpoint declares the
 * access module's permissions, as every module's does.
 *
 * <ul>
 *   <li>{@code application}: the streams open on this instance and the listener that hands them
 *       events;
 *   <li>{@code domain}: which events a stream may resume from, and who may receive each type, in
 *       plain Java;
 *   <li>{@code web}: the stream's endpoint and the connection that writes to its client.
 * </ul>
 */
@ApplicationModule(
        displayName = "Notifications",
        allowedDependencies = {"shared", "access", "tenancy", "billing", "events"})
@NullMarked
package dev.axion.core.notifications;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
