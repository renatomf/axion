/**
 * Domain events after their transaction (events and webhooks design, section 3.2): the schema of
 * Spring Modulith's publication registry, the core's transactional outbox. Publishers need nothing
 * from this module; they publish with Spring's {@code ApplicationEventPublisher}.
 */
@ApplicationModule(displayName = "Events", allowedDependencies = "shared")
@NullMarked
package dev.axion.core.events;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
