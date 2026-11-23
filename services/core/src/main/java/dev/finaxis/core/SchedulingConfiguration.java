package dev.finaxis.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs @Scheduled methods, such as the ledger's balance verifier, on the pool that
// spring.task.scheduling.pool.size sizes. A job never overlaps itself.
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {}
