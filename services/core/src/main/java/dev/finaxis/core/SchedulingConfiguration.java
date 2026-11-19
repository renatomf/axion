package dev.finaxis.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's task scheduler, which runs every bean method annotated with {@code @Scheduled}
 * on its own timer, such as the ledger's balance verifier.
 *
 * <p>Without this annotation, {@code @Scheduled} is silently ignored. The scheduler runs tasks one
 * at a time on a single thread unless {@code spring.task.scheduling.pool.size} says otherwise.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {}
