/**
 * Domain events after their transaction (events and webhooks design, section 3.2): the schema of
 * Spring Modulith's publication registry, the core's transactional outbox, the executor listeners
 * run on, and the jobs that resubmit failed publications, measure them and remove completed ones.
 * Publishers need nothing from this module; they publish with Spring's {@code
 * ApplicationEventPublisher}.
 *
 * <p>{@link dev.axion.core.events.EventCatalog} turns domain events into the public events that leave
 * the core; it reads the publishing modules' events and representations, never their services.
 */
@ApplicationModule(
        displayName = "Events",
        allowedDependencies = {"shared", "billing", "billing :: representation"})
@NullMarked
package dev.axion.core.events;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
