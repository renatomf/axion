package dev.axion.core.audit;

import dev.axion.core.shared.TenantId;

/** An audit chain: each tenant has one (events and webhooks design, section 9.1). */
public record ChainId(TenantId tenant) {}
