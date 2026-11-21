package dev.finaxis.simulator;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs @Scheduled methods, such as the delivery dispatcher, one at a time on one thread.
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {}
