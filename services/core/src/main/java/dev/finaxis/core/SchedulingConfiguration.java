package dev.finaxis.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs @Scheduled methods, such as the ledger's balance verifier, one at a time on one thread.
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {}
